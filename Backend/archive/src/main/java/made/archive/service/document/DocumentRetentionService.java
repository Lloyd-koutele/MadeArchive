package made.archive.service.document;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.repository.DocumentRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.storage.StorageService;

/**
 * Fin de vie des documents. Deux voies de purge, toutes deux "tombstone" (le
 * contenu disparaît réellement — fichiers MinIO + entrée Meilisearch — mais la
 * ligne Document et son historique restent en base comme preuve que le document
 * a existé et a été archivé, status passe à DELETED) :
 *
 *   1. retentionUntil atteint — automatique, jamais déclenché manuellement.
 *   2. suppressionPrevueLe atteint — un document en CORBEILLE (envoyé là par
 *      un éditeur, voir DocumentService.envoyerCorbeille — n'importe quel
 *      document, plus seulement un corrompu), après un délai de grâce de 3
 *      jours pendant lequel il reste consultable et restaurable. C'est la
 *      SEULE voie de suppression manuelle du système.
 *
 * Important : Meilisearch n'a aucune connaissance des suppressions côté base
 * ou stockage. C'est cette classe qui doit explicitement lui dire de retirer
 * le document, sinon il resterait indéfiniment trouvable en recherche malgré
 * la disparition de son contenu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentRetentionService
{
    private final DocumentRepository documentRepository;
    private final StorageService     storageService;
    private final MeilisearchService meilisearchService;
    private final AuditLogService    auditLogService;

    @Transactional
    public void purgeExpiredDocuments()
    {
        List<Document> expired = documentRepository
            .findByRetentionUntilLessThanEqualAndStatusNot(LocalDate.now(), DocumentStatus.DELETED);

        if (expired.isEmpty())
        {
            return;
        }

        log.info("[Retention] {} document(s) ont atteint leur fin de rétention", expired.size());

        for (Document document : expired)
        {
            purgeOne(document, "Fin de rétention atteinte le " + document.getRetentionUntil());
        }
    }

    /**
     * Documents en CORBEILLE dont le délai de grâce de 3 jours est écoulé —
     * voir DocumentService.envoyerCorbeille.
     */
    @Transactional
    public void purgeDocumentsCorbeille()
    {
        List<Document> aPurger = documentRepository
            .findByStatusAndSuppressionPrevueLeLessThanEqual(DocumentStatus.CORBEILLE, LocalDate.now());

        if (aPurger.isEmpty())
        {
            return;
        }

        log.info("[Retention] {} document(s) en corbeille, délai de grâce écoulé", aPurger.size());

        for (Document document : aPurger)
        {
            purgeOne(document, "Suppression demandée par l'éditeur (corbeille), "
                + "délai de grâce de 3 jours écoulé");
        }
    }

    /**
     * Resynchronise Meilisearch avec la base — retire les entrées "fantômes" (indexées
     * mais dont le document est absent ou déjà DELETED en base). Cas typique : un
     * document supprimé directement en base ou en stockage, en dehors de purgeOne()
     * ci-dessus — Meilisearch n'a alors jamais été notifié (voir Javadoc de la classe :
     * "Meilisearch n'a aucune connaissance des suppressions côté base ou stockage").
     * Idempotent, sans effet si tout est déjà cohérent — appelable à volonté.
     *
     * @return le nombre d'entrées fantômes retirées.
     */
    @Transactional(readOnly = true)
    public int resynchroniserMeilisearch()
    {
        List<String> idsIndexes = meilisearchService.listerTousLesIdsIndexes();
        if (idsIndexes.isEmpty())
        {
            return 0;
        }

        java.util.Set<String> idsVivants = documentRepository.findAllIdsNonSupprimes().stream()
            .map(UUID::toString)
            .collect(java.util.stream.Collectors.toSet());

        List<String> fantomes = idsIndexes.stream()
            .filter(id -> !idsVivants.contains(id))
            .toList();

        if (fantomes.isEmpty())
        {
            log.info("[Retention] Resynchronisation Meilisearch : rien à nettoyer ({} document(s) indexé(s))",
                idsIndexes.size());
            return 0;
        }

        log.warn("[Retention] Resynchronisation Meilisearch : {} entrée(s) fantôme(s) détectée(s) sur {} : {}",
            fantomes.size(), idsIndexes.size(), fantomes);
        meilisearchService.deleteDocuments(fantomes);

        return fantomes.size();
    }

    private void purgeOne(Document document, String raisonAudit)
    {
        UUID id = document.getId();

        // 1. Tombstone D'ABORD, PUIS suppression physique — jamais l'inverse.
        //    C'est le changement d'état en base (status → DELETED) qui déclenche la
        //    suppression du fichier, pas le contraire : tant que la ligne reste ACTIVE,
        //    le garde-fou de supprimerFichierMinioSiOrphelin() (voir plus bas) refuse
        //    de toucher au fichier. Ordre inversé auparavant (fichier supprimé avant le
        //    flip de statut) — fonctionnellement correct ici (dernière étape avant purge),
        //    mais dangereux comme modèle à copier ailleurs : n'importe quel autre appelant
        //    supprimant le fichier AVANT ce flip se retrouvait avec un document encore actif
        //    en base mais un fichier déjà parti. C'est précisément ce qui est arrivé en
        //    dehors de l'appli (suppression manuelle directe sur MinIO) — le garde-fou ne
        //    protège que les appels internes, mais l'ordre correct reste la bonne pratique
        //    partout dans le code.
        document.setStatus(DocumentStatus.DELETED);
        documentRepository.save(document);

        // 2. Purge du PDF/A dans MinIO — best-effort, un échec ici ne bloque
        //    pas la purge des autres documents. Le fichier original n'a jamais
        //    été stocké (seul son SHA-256 est gardé), rien d'autre à purger.
        supprimerFichierMinioSiOrphelin(document.getStorageKey(), id, "PDF/A");

        // 2b. Texte OCR (voir OcrService.storeOcrText) — clé construite à la volée
        //    depuis l'ID, jamais stockée sur l'entité Document : le garde-fou de
        //    supprimerFichierMinioSiOrphelin() ne peut donc rien vérifier dessus
        //    (aucune colonne ne la référence), il l'autorisera toujours. Correct ici
        //    puisqu'appelé après le flip de statut ci-dessus, mais gardez ça en tête
        //    si ce fichier est un jour référencé ailleurs.
        //    Manquait jusqu'ici : "le contenu disparaît réellement" (voir Javadoc de
        //    la classe) ne s'appliquait qu'au PDF/A + Meilisearch, pas à ce fichier —
        //    resté orphelin en MinIO à chaque suppression.
        supprimerFichierMinioSiOrphelin("ocr/" + id + ".txt", id, "texte OCR");

        // 3. Retrait explicite de Meilisearch — voir Javadoc de la classe.
        try
        {
            meilisearchService.deleteDocument(id.toString());
        }
        catch (Exception e)
        {
            log.warn("[Retention] Best-effort : échec de retrait Meilisearch pour {} : {}",
                id, e.getMessage(), e);
        }

        log.info("[Retention] Document {} purgé ({})", id, raisonAudit);

        // Acteur système (null) : ce déclenchement est toujours un batch planifié,
        // jamais une action HTTP d'un utilisateur au moment de l'exécution réelle.
        auditLogService.log(null, AuditAction.DOCUMENT_SUPPRIME_DEFINITIVEMENT, AuditCible.DOCUMENT,
            id.toString(),
            document.getUniteOrganisationnelle() != null ? document.getUniteOrganisationnelle().getId() : null,
            "Document \"" + document.getTitre() + "\" supprimé définitivement — " + raisonAudit,
            true);
    }

    private void supprimerFichierMinioSiOrphelin(String key, UUID documentId, String label)
    {
        supprimerFichierMinioSiOrphelin(key, "[Retention] (" + label + ", document " + documentId + ")");
    }

    /**
     * Point d'entrée UNIQUE pour supprimer physiquement un fichier MinIO appartenant à
     * un document — TypeDocumentService et DocumentUploadeService l'utilisent aussi,
     * au lieu d'appeler StorageService.delete() directement. Garde-fou : refuse si un
     * document ACTIF (status != DELETED) référence encore cette clé — protège contre
     * un bug applicatif qui supprimerait le fichier d'un document encore vivant en base.
     *
     * Limite assumée : ceci ne protège que les appels FAITS PAR L'APPLICATION. Un accès
     * direct à MinIO (client mc, console web, autre credentials admin) contourne
     * entièrement ce garde-fou — MinIO n'a aucune connaissance de Postgres. La seule
     * protection contre ÇA est côté infra : restreindre qui détient des credentials MinIO
     * admin, et/ou activer le versioning du bucket (objets supprimés récupérables).
     */
    public void supprimerFichierMinioSiOrphelin(String key, String contexteLog)
    {
        if (key == null || key.isBlank())
        {
            return;
        }

        if (documentRepository.existsByStorageKeyAndStatusNot(key, DocumentStatus.DELETED))
        {
            log.error("[Retention] REFUS de suppression MinIO — {} est encore référencée par un "
                + "document ACTIF (incohérence appelant/état, à investiguer) : {}", key, contexteLog);
            return;
        }

        try
        {
            storageService.delete(key);
        }
        catch (Exception e)
        {
            log.warn("[Retention] Best-effort : échec de suppression MinIO {} : {}",
                contexteLog, e.getMessage(), e);
        }
    }
}
