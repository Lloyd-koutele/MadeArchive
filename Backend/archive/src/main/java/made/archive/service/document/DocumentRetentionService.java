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
import made.archive.entite.MotifSuppression;
import made.archive.entite.NotificationType;
import made.archive.entite.Role_Name;
import made.archive.entite.SortFinal;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.UserRepository;
import made.archive.service.notification.NotificationService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.storage.StorageService;

/**
 * Fin de vie des documents — la CORBEILLE est le mécanisme unique, avec un délai de grâce pendant
 * lequel l'éditeur peut agir (restaurer, bloquer la suppression), et un MOTIF conservé partout :
 *
 *   1. Suppression par un éditeur (DocumentService.envoyerCorbeille) : motif obligatoire
 *      (erreur d'archivage, suppression légale, autre + commentaire). Purge automatique à
 *      l'échéance du délai de grâce, quel que soit le sort final du type.
 *   2. Échéance de conservation atteinte (purgeExpiredDocuments) : mise en corbeille par le système,
 *      motif FIN_DE_VIE. Purge automatique SEULEMENT si le sort final du type est DETRUIRE ; pour
 *      CONSERVER/TRIER le document reste en corbeille jusqu'à ce qu'un éditeur le supprime (avec motif)
 *      ou le restaure — voir supprimerDefinitivementManuellement.
 *
 * Le délai de grâce est celui du type (Retention.periodGrace), 6 jours par défaut — voir
 * DocumentService.delaiGraceJours. Un document dont l'éditeur a bloqué l'élimination n'est JAMAIS
 * purgé tant qu'il n'est pas débloqué. Chaque jour, alerterSuppressionsImminentes prévient les éditeurs
 * des documents que le système va supprimer dans moins de JOURS_ALERTE_SUPPRESSION jours.
 *
 * La purge réelle (purgeOne) est "tombstone" : le contenu disparaît réellement — fichiers MinIO +
 * entrée Meilisearch — mais la ligne Document, son motif et son historique restent en base comme
 * preuve que le document a existé et a été archivé, status passe à DELETED.
 *
 * Important : Meilisearch n'a aucune connaissance des suppressions côté base ou stockage. C'est cette
 * classe qui doit explicitement lui dire de retirer le document, sinon il resterait indéfiniment
 * trouvable en recherche malgré la disparition de son contenu.
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
    private final NotificationService notificationService;
    private final UserRepository     userRepository;

    /** Une alerte par jour tant qu'il reste au plus ce nombre de jours avant la suppression automatique. */
    public static final int JOURS_ALERTE_SUPPRESSION = 3;

    private static final int LONGUEUR_MAX_TEXTE = 500;

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
     * Met un document à la corbeille suite à sa fin de rétention — motif FIN_DE_VIE, acteur système
     * (null) : ce déclenchement est toujours un batch planifié. La purge réelle suit via
     * purgeDocumentsCorbeille (DETRUIRE seulement) une fois le délai de grâce du type écoulé.
     */
    private void envoyerCorbeilleAutomatique(Document document)
    {
        DocumentStatus statutOrigine = document.getStatus();
        long delai = DocumentService.delaiGraceJours(document.getTypeDocument());
        document.setStatutAvantCorbeille(statutOrigine);
        document.setStatus(DocumentStatus.CORBEILLE);
        document.setMotifSuppression(MotifSuppression.FIN_DE_VIE);
        document.setCommentaireSuppression(null);
        document.setEliminationBloquee(false);
        document.setAlerteSuppressionLe(null);
        document.setSuppressionPrevueLe(LocalDate.now().plusDays(delai));
        documentRepository.save(document);
        meilisearchService.updateDocumentStatus(document);

        log.info("[Retention] Document {} (fin de rétention le {}) envoyé à la corbeille — "
            + "suppression définitive prévue le {}",
            document.getId(), document.getRetentionUntil(), document.getSuppressionPrevueLe());

        boolean automatique = estSupprimableAutomatiquement(document);
        auditLogService.log(null, AuditAction.DOCUMENT_PLACE_CORBEILLE, AuditCible.DOCUMENT,
            document.getId().toString(),
            document.getUniteOrganisationnelle() != null ? document.getUniteOrganisationnelle().getId() : null,
            "Document \"" + document.getTitre() + "\" (" + statutOrigine + ") envoyé automatiquement à la "
                + "corbeille — motif : fin de vie du document (échéance de conservation du "
                + document.getRetentionUntil() + ")"
                + (automatique
                    ? ", suppression automatique prévue le " + document.getSuppressionPrevueLe()
                    : " — conservation permanente : il y reste jusqu'à décision d'un éditeur"),
            true, java.util.Map.of("motif", MotifSuppression.FIN_DE_VIE.name()));
    }

    /**
     * Le système supprimera-t-il CE document tout seul, sans action de l'éditeur, à l'échéance du délai
     * de grâce ? Oui pour une suppression volontaire (n'importe quel type), et pour une fin de vie
     * uniquement si le sort final du type est DETRUIRE. Ne tient PAS compte du blocage (voir
     * Document.eliminationBloquee). RÈGLE UNIQUE : tout ce qui décide d'une purge automatique ou
     * l'annonce à l'utilisateur passe par ici.
     */
    public static boolean estSupprimableAutomatiquement(Document document)
    {
        if (document.getStatus() != DocumentStatus.CORBEILLE)
        {
            return false;
        }
        return document.getMotifSuppression() != MotifSuppression.FIN_DE_VIE
            || resolveSortFinal(document) == SortFinal.DETRUIRE;
    }

    private static SortFinal resolveSortFinal(Document document)
    {
        return document.getTypeDocument().getRetention().getSortFinal();
    }

    /**
     * Documents en CORBEILLE dont le délai de grâce est écoulé : purge automatique de ceux que le
     * système doit supprimer (estSupprimableAutomatiquement) ET dont l'élimination n'est pas bloquée.
     * Les autres (fin de vie CONSERVER/TRIER, ou bloqués) restent en corbeille. Ils restent candidats
     * à chaque passage — simple skip, coût négligeable vu le volume attendu.
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
            if (document.isEliminationBloquee() || !estSupprimableAutomatiquement(document))
            {
                continue;
            }

            String raison = document.getMotifSuppression() == MotifSuppression.FIN_DE_VIE
                ? "fin de vie du document (échéance de conservation atteinte, sort final DETRUIRE, délai de grâce écoulé)"
                : "délai de grâce écoulé après suppression" + libelleMotif(document);
            purgeOne(document, raison, null);
            purges++;
        }

        log.info("[Retention] {} document(s) en corbeille avec délai de grâce écoulé examiné(s), "
            + "{} purgé(s), {} laissé(s) en corbeille (conservation permanente ou élimination bloquée)",
            candidats.size(), purges, candidats.size() - purges);
    }

    /**
     * Suppression définitive MANUELLE par un éditeur, avec motif — seule issue pour un document arrivé
     * en fin de vie mais dont le sort final (CONSERVER/TRIER) exclut la purge automatique ; possible dès
     * son arrivée en corbeille, sans attendre. Refusée pour tout document que le système supprimera de
     * lui-même (on restaure ou on bloque, on ne court-circuite pas le délai de grâce) et pour un
     * document dont l'élimination est bloquée. L'autorisation (éditeur ayant accès) est vérifiée par
     * l'appelant (DocumentService.supprimerDefinitivementDepuisCorbeille).
     */
    @Transactional
    public void supprimerDefinitivementManuellement(Document document, User acteur,
                                                    MotifSuppression motif, String commentaire)
    {
        if (document.getStatus() != DocumentStatus.CORBEILLE)
        {
            throw new BusinessException("Ce document n'est pas dans la corbeille");
        }
        if (document.isEliminationBloquee())
        {
            throw new BusinessException("La suppression de ce document est bloquée — débloquez-la d'abord");
        }
        if (estSupprimableAutomatiquement(document))
        {
            throw new BusinessException("Ce document sera supprimé automatiquement le "
                + document.getSuppressionPrevueLe() + " — d'ici là vous pouvez le restaurer ou bloquer sa suppression");
        }
        validerMotifUtilisateur(motif, commentaire);

        document.setMotifSuppression(motif);
        document.setCommentaireSuppression("(document arrivé en fin de vie) " + (commentaire != null ? commentaire.trim() : "")
            .trim());
        purgeOne(document, "suppression définitive demandée par "
            + (acteur != null ? acteur.getEmail() : "un éditeur") + libelleMotif(document), acteur);
    }

    /** Bloque la suppression automatique d'un document en corbeille — motif obligatoire. */
    @Transactional
    public void bloquerElimination(Document document, User acteur, String motif)
    {
        if (document.getStatus() != DocumentStatus.CORBEILLE)
        {
            throw new BusinessException("Seul un document en corbeille peut voir sa suppression bloquée");
        }
        if (!estSupprimableAutomatiquement(document))
        {
            throw new BusinessException("Ce document n'est pas supprimé automatiquement : il n'y a rien à bloquer");
        }
        if (document.isEliminationBloquee())
        {
            throw new BusinessException("La suppression de ce document est déjà bloquée");
        }
        String motifNet = texteObligatoire(motif, "Le motif du blocage");

        document.setEliminationBloquee(true);
        document.setBlocageMotif(motifNet);
        document.setBlocagePar(acteur.getId());
        document.setBlocageLe(java.time.Instant.now());
        documentRepository.save(document);

        auditLogService.log(acteur, AuditAction.DOCUMENT_ELIMINATION_BLOQUEE, AuditCible.DOCUMENT,
            document.getId().toString(), uoId(document),
            "Suppression automatique du document \"" + document.getTitre() + "\" bloquée par "
                + acteur.getEmail() + " — motif : " + motifNet, true,
            java.util.Map.of("motif", motifNet));
    }

    /** Débloque : le document redevient supprimable automatiquement, avec un délai de grâce COMPLET. */
    @Transactional
    public void debloquerElimination(Document document, User acteur, String motif)
    {
        if (document.getStatus() != DocumentStatus.CORBEILLE || !document.isEliminationBloquee())
        {
            throw new BusinessException("La suppression de ce document n'est pas bloquée");
        }
        String motifNet = texteObligatoire(motif, "Le motif du déblocage");

        long delai = DocumentService.delaiGraceJours(document.getTypeDocument());
        document.setEliminationBloquee(false);
        document.setBlocageMotif(null);
        document.setBlocagePar(null);
        document.setBlocageLe(null);
        document.setAlerteSuppressionLe(null);
        document.setSuppressionPrevueLe(LocalDate.now().plusDays(delai));
        documentRepository.save(document);

        auditLogService.log(acteur, AuditAction.DOCUMENT_ELIMINATION_DEBLOQUEE, AuditCible.DOCUMENT,
            document.getId().toString(), uoId(document),
            "Suppression automatique du document \"" + document.getTitre() + "\" débloquée par "
                + acteur.getEmail() + " — motif : " + motifNet + ". Nouvelle échéance : "
                + document.getSuppressionPrevueLe(), true,
            java.util.Map.of("motif", motifNet));
    }

    /**
     * Une fois par jour : prévient les éditeurs de l'UO des documents que le système va supprimer dans
     * moins de JOURS_ALERTE_SUPPRESSION jours (un message regroupé par UO, type et date — jamais un par
     * document). Un document déjà alerté aujourd'hui, bloqué, ou que le système ne supprimera pas
     * (conservation permanente) est ignoré. À appeler APRÈS la purge du jour (voir le scheduler).
     */
    @Transactional
    public void alerterSuppressionsImminentes()
    {
        LocalDate aujourdhui = LocalDate.now();
        List<Document> proches = documentRepository.findByStatusAndSuppressionPrevueLeBetween(
            DocumentStatus.CORBEILLE, aujourdhui.plusDays(1), aujourdhui.plusDays(JOURS_ALERTE_SUPPRESSION));

        java.util.Map<String, List<Document>> groupes = proches.stream()
            .filter(d -> !d.isEliminationBloquee() && estSupprimableAutomatiquement(d))
            .filter(d -> !aujourdhui.equals(d.getAlerteSuppressionLe()))
            .collect(java.util.stream.Collectors.groupingBy(d ->
                uoId(d) + "|" + d.getTypeDocument().getId() + "|" + d.getSuppressionPrevueLe(),
                java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));

        for (List<Document> groupe : groupes.values())
        {
            Document modele = groupe.get(0);
            Long uo = uoId(modele);
            List<User> editeurs = uo == null ? List.of()
                : userRepository.findByUniteOrganisationnelleId(uo).stream()
                    .filter(u -> u.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.EDITOR))
                    .toList();
            if (editeurs.isEmpty())
            {
                log.warn("[Retention] Alerte de suppression imminente non envoyée : aucun éditeur dans l'UO {}", uo);
                continue;
            }

            long jours = java.time.temporal.ChronoUnit.DAYS.between(aujourdhui, modele.getSuppressionPrevueLe());
            int n = groupe.size();
            notificationService.notifier(editeurs, NotificationType.DOCUMENT_SUPPRESSION_IMMINENTE,
                n + " document" + (n > 1 ? "s" : "") + " de type « " + modele.getTypeDocument().getNom() + " » "
                    + (n > 1 ? "seront supprimés" : "sera supprimé") + " définitivement "
                    + (jours <= 1 ? "demain" : "dans " + jours + " jours") + " (" + modele.getSuppressionPrevueLe()
                    + "). Restaurez-" + (n > 1 ? "les" : "le") + " ou bloquez leur suppression depuis la corbeille si nécessaire.");

            groupe.forEach(d -> d.setAlerteSuppressionLe(aujourdhui));
            documentRepository.saveAll(groupe);
        }
    }

    /** Motif saisi par un utilisateur : jamais FIN_DE_VIE (réservé au système), AUTRE exige un commentaire. */
    public static void validerMotifUtilisateur(MotifSuppression motif, String commentaire)
    {
        if (motif == null)
        {
            throw new BusinessException("Le motif de suppression est obligatoire");
        }
        if (motif == MotifSuppression.FIN_DE_VIE)
        {
            throw new BusinessException("Le motif « fin de vie » est réservé au système");
        }
        if (motif == MotifSuppression.AUTRE && (commentaire == null || commentaire.isBlank()))
        {
            throw new BusinessException("Précisez la raison de la suppression (motif « autre »)");
        }
        if (commentaire != null && commentaire.trim().length() > LONGUEUR_MAX_TEXTE)
        {
            throw new BusinessException("Le commentaire est limité à " + LONGUEUR_MAX_TEXTE + " caractères");
        }
    }

    private static String texteObligatoire(String valeur, String libelle)
    {
        if (valeur == null || valeur.isBlank())
        {
            throw new BusinessException(libelle + " est obligatoire");
        }
        String net = valeur.trim();
        if (net.length() > LONGUEUR_MAX_TEXTE)
        {
            throw new BusinessException(libelle + " est limité à " + LONGUEUR_MAX_TEXTE + " caractères");
        }
        return net;
    }

    private static Long uoId(Document document)
    {
        return document.getUniteOrganisationnelle() != null ? document.getUniteOrganisationnelle().getId() : null;
    }

    private static String libelleMotif(Document d)
    {
        if (d.getMotifSuppression() == null) return " (motif non renseigné)";
        return " (motif : " + d.getMotifSuppression()
            + (d.getCommentaireSuppression() != null && !d.getCommentaireSuppression().isBlank()
                ? " — " + d.getCommentaireSuppression() : "") + ")";
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

        // GARDE UNIQUE — dernier rempart avant toute destruction, quelle que soit la voie d'appel :
        // seul un document en corbeille et dont l'élimination n'est PAS bloquée peut être purgé.
        if (document.getStatus() != DocumentStatus.CORBEILLE || document.isEliminationBloquee())
        {
            log.error("[Retention] REFUS de purge du document {} (statut {}, élimination bloquée : {}) — "
                + "appelant incohérent, à investiguer", id, document.getStatus(), document.isEliminationBloquee());
            return;
        }

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
        document.setElimineLe(java.time.Instant.now());
        document.setEliminePar(acteur != null ? acteur.getId() : null);
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
            true, java.util.Map.of(
                "motif", document.getMotifSuppression() != null ? document.getMotifSuppression().name() : "NON_RENSEIGNE",
                "automatique", acteur == null));
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
