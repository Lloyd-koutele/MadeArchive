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
import made.archive.entite.SortFinal;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.storage.StorageService;

/**
 * Fin de vie des documents — UN SEUL mécanisme de grâce, deux déclencheurs :
 *
 *   1. retentionUntil atteint — automatique (purgeExpiredDocuments), jamais
 *      déclenché manuellement. N'envoie plus directement à la purge : le
 *      document est d'abord mis à la corbeille (voir envoyerCorbeilleAutomatique),
 *      exactement comme s'il y avait été envoyé par un éditeur.
 *   2. suppressionPrevueLe atteint — un document en CORBEILLE, qu'il y soit
 *      arrivé manuellement (DocumentService.envoyerCorbeille) ou
 *      automatiquement (1. ci-dessus), après un délai de grâce unique
 *      (DocumentService.DELAI_GRACE_CORBEILLE_JOURS) pendant lequel il reste
 *      consultable et restaurable.
 *
 * La purge réelle (purgeOne) est "tombstone" : le contenu disparaît réellement —
 * fichiers MinIO + entrée Meilisearch — mais la ligne Document et son historique
 * restent en base comme preuve que le document a existé et a été archivé,
 * status passe à DELETED. Déclenchée automatiquement depuis purgeDocumentsCorbeille
 * (ci-dessous) UNIQUEMENT quand le sort final du type de document est DETRUIRE
 * (voir SortFinal) — CONSERVER/TRIER laissent le document en CORBEILLE
 * indéfiniment, une suppression définitive restant alors possible à tout moment
 * mais exclusivement MANUELLE (voir supprimerDefinitivementManuellement, appelée
 * depuis DocumentService à la demande explicite d'un éditeur).
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
        List<Document> expired = documentRepository.findByRetentionUntilLessThanEqualAndStatusNotIn(
            LocalDate.now(), List.of(DocumentStatus.DELETED, DocumentStatus.CORBEILLE));

        if (expired.isEmpty())
        {
            return;
        }

        log.info("[Retention] {} document(s) ont atteint leur fin de rétention — envoi à la corbeille",
            expired.size());

        for (Document document : expired)
        {
            envoyerCorbeilleAutomatique(document);
        }
    }

    /**
     * Met un document à la corbeille suite à sa fin de rétention légale —
     * même effet que DocumentService.envoyerCorbeille (déclenchée par un
     * éditeur), mais un acteur système (null) : ce déclenchement est
     * toujours un batch planifié, jamais une action HTTP d'un utilisateur au
     * moment de l'exécution réelle. La purge réelle suit son cours normal
     * via purgeDocumentsCorbeille une fois le délai de grâce écoulé.
     */
    private void envoyerCorbeilleAutomatique(Document document)
    {
        DocumentStatus statutOrigine = document.getStatus();
        document.setStatutAvantCorbeille(statutOrigine);
        document.setStatus(DocumentStatus.CORBEILLE);
        document.setSuppressionPrevueLe(LocalDate.now().plusDays(DocumentService.DELAI_GRACE_CORBEILLE_JOURS));
        documentRepository.save(document);

        log.info("[Retention] Document {} (fin de rétention le {}) envoyé à la corbeille — "
            + "suppression définitive prévue le {}",
            document.getId(), document.getRetentionUntil(), document.getSuppressionPrevueLe());

        auditLogService.log(null, AuditAction.DOCUMENT_PLACE_CORBEILLE, AuditCible.DOCUMENT,
            document.getId().toString(),
            document.getUniteOrganisationnelle() != null ? document.getUniteOrganisationnelle().getId() : null,
            "Document \"" + document.getTitre() + "\" (" + statutOrigine + ") envoyé automatiquement à la "
                + "corbeille — fin de rétention atteinte le " + document.getRetentionUntil()
                + ", suppression définitive prévue le " + document.getSuppressionPrevueLe(),
            true);
    }

    /**
     * Documents en CORBEILLE dont le délai de grâce (voir
     * DocumentService.DELAI_GRACE_CORBEILLE_JOURS) est écoulé — qu'ils y
     * soient arrivés manuellement (DocumentService.envoyerCorbeille) ou
     * automatiquement (purgeExpiredDocuments ci-dessus). Le SORT FINAL du type
     * de document (voir SortFinal) décide seul de ce qui se passe à ce moment,
     * quelle que soit la cause d'entrée en corbeille — les deux partagent le
     * même délai de grâce et la même décision :
     *   - DETRUIRE  : purge automatique, comportement historique inchangé.
     *   - CONSERVER / TRIER : aucune purge automatique — le document reste en
     *     CORBEILLE indéfiniment (toujours consultable/restaurable comme avant
     *     ce délai), une suppression définitive restant possible à tout moment
     *     mais exclusivement manuelle (voir supprimerDefinitivementManuellement).
     *     Note : ce cas reste candidat à chaque passage de ce job tant qu'aucune
     *     suppression manuelle n'a eu lieu — coût négligeable (simple skip) vu le
     *     faible volume attendu, préféré à une colonne de suivi supplémentaire.
     */
    @Transactional
    public void purgeDocumentsCorbeille()
    {
        List<Document> candidats = documentRepository
            .findByStatusAndSuppressionPrevueLeLessThanEqual(DocumentStatus.CORBEILLE, LocalDate.now());

        if (candidats.isEmpty())
        {
            return;
        }

        int purges = 0;
        for (Document document : candidats)
        {
            if (resolveSortFinal(document) != SortFinal.DETRUIRE)
            {
                continue;
            }

            purgeOne(document, "Délai de grâce de la corbeille (" + DocumentService.DELAI_GRACE_CORBEILLE_JOURS
                + " jours) écoulé sans restauration — sort final DETRUIRE", null);
            purges++;
        }

        log.info("[Retention] {} document(s) en corbeille avec délai de grâce écoulé examiné(s), "
            + "{} purgé(s) (sort final DETRUIRE), {} conservé(s) en corbeille (CONSERVER/TRIER)",
            candidats.size(), purges, candidats.size() - purges);
    }

    private SortFinal resolveSortFinal(Document document)
    {
        return document.getTypeDocument().getRetention().getSortFinal();
    }

    /**
     * Suppression définitive MANUELLE depuis la corbeille, demandée par un
     * éditeur — seule issue possible pour un document dont le sort final
     * (CONSERVER/TRIER) exclut la purge automatique (voir purgeDocumentsCorbeille).
     * L'autorisation (éditeur ayant accès au document) est vérifiée par
     * l'appelant (voir DocumentService.supprimerDefinitivementDepuisCorbeille) —
     * ce service ne vérifie ici que les règles métier propres au cycle de vie
     * du document lui-même, pas qui a le droit de les déclencher.
     */
    @Transactional
    public void supprimerDefinitivementManuellement(Document document, User acteur)
    {
        if (document.getStatus() != DocumentStatus.CORBEILLE)
        {
            throw new BusinessException("Ce document n'est pas dans la corbeille");
        }

        if (document.getSuppressionPrevueLe() == null || document.getSuppressionPrevueLe().isAfter(LocalDate.now()))
        {
            throw new BusinessException("Le délai de grâce de la corbeille ("
                + DocumentService.DELAI_GRACE_CORBEILLE_JOURS + " jours) n'est pas encore écoulé pour ce document");
        }

        purgeOne(document, "Suppression définitive manuelle demandée par "
            + (acteur != null ? acteur.getEmail() : "un éditeur") + " (sort final CONSERVER/TRIER, "
            + "délai de grâce écoulé mais purge automatique exclue)", acteur);
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

    private void purgeOne(Document document, String raisonAudit, User acteur)
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

        // acteur == null pour un déclenchement automatique (batch planifié, voir
        // purgeDocumentsCorbeille) ; un véritable utilisateur pour une suppression
        // manuelle (voir supprimerDefinitivementManuellement) — distingue les deux
        // dans le journal sans avoir besoin d'une AuditAction séparée.
        auditLogService.log(acteur, AuditAction.DOCUMENT_SUPPRIME_DEFINITIVEMENT, AuditCible.DOCUMENT,
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
