package made.archive.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.CheckResult;
import made.archive.entite.Document;
import made.archive.service.storage.StorageService;
import made.archive.entite.DocumentStatus;
import made.archive.entite.FixityCheckResult;
import made.archive.entite.NotificationType;
import made.archive.entite.Role_Name;
import made.archive.entite.User;
import made.archive.repository.DocumentRepository;
import made.archive.repository.FixityCheckResultRepository;
import made.archive.repository.UserRepository;
import made.archive.exception.CleChiffrementException;
import made.archive.security.ControleCleChiffrementService;
import made.archive.security.DocumentEncryptionService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.integrite.AlerteIntegriteService;
import made.archive.service.integrite.PreuveIntegriteService;
import made.archive.service.notification.NotificationService;
import made.archive.service.organisation.UniteOrganisationnelleService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FixityCheckService
{
    private final StorageService storageService;
    private final HashService hashService;
    private final DocumentRepository documentRepository;
    private final FixityCheckResultRepository fixityCheckResultRepository;
    private final DocumentEncryptionService documentEncryptionService;
    private final NotificationService notificationService;
    private final UniteOrganisationnelleService uniteOrganisationnelleService;
    private final DocumentService documentService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final MeilisearchService meilisearchService;
    private final PreuveIntegriteService preuveIntegriteService;
    private final AlerteIntegriteService alerteIntegriteService;
    private final ControleCleChiffrementService controleCle;

    /**
     * Vérifie l'intégrité d'une liste de documents par leurs IDs.
     */
    @Transactional
    public List<Document> verifyDocumentsByIds(List<UUID> documentIds) 
    {
        verifierCleAvantControle();
        try
        {
            List<Document> documents = documentRepository.findAllById(documentIds);
        return documents.stream()
            .peek(this::verifyAndSave)
            .collect(Collectors.toList());
        }
        catch(Exception e)
        {
            throw new RuntimeException("Erreur lors verification des integrites des documents", e);
        }
    }

    /**
     * Vérifie l'intégrité de TOUS les documents d'un type spécifique.
     */
    @Transactional
    public List<Document> verifyDocumentsByType(Long typeDocumentId) 
    {
        verifierCleAvantControle();
        try
        {
            List<Document> documents = documentRepository.findByTypeDocument_Id(typeDocumentId);
            return documents.stream()
                .peek(this::verifyAndSave)
                .collect(Collectors.toList());
        }
        catch(Exception e)
        {
            throw new RuntimeException("Erreur lors verification des integrites des documents", e);
        }
    }

    /**
     * Vérifie TOUS les documents de la plateforme.
     */
    @Transactional
    public List<Document> verifyAllDocuments() 
    {
        verifierCleAvantControle();
        try
        {
            List<Document> documents = documentRepository.findAll();
            return documents.stream()
                .peek(this::verifyAndSave)
                .collect(Collectors.toList());
        }
        catch(Exception e)
        {
            throw new RuntimeException("Erreur lors verification des integrites des documents", e);
        }
    }

    /**
     * Contrôle la clé de chiffrement AVANT de parcourir les documents : une clé absente ou différente de celle des
     * archives fait échouer tout déchiffrement, ce qui ressemblerait à une corruption de masse. On s'arrête, on
     * alerte l'administration, et aucun document n'est touché.
     */
    private void verifierCleAvantControle()
    {
        try
        {
            controleCle.exigerCleValide();
        }
        catch (CleChiffrementException e)
        {
            log.error("[Fixity] Contrôle d'intégrité suspendu : {}", e.getMessage());
            alerteIntegriteService.cleChiffrementAnormale(e.getMessage());
            throw e;
        }
    }

    /**
     * Vérifie un document individuel et enregistre le résultat.
     *
     * Télécharge les octets DÉJÀ ARCHIVÉS dans MinIO (chiffrés au repos),
     * les déchiffre, puis recalcule leur SHA-256 — jamais de reconversion du
     * fichier source. C'est important : la conversion PDF/A embarque un
     * horodatage (PdfAConversionService), donc reconvertir donnerait un hash
     * différent à chaque fois. Ici on rehash les mêmes octets en clair déjà
     * obtenus à l'upload, donc la comparaison est stable : un écart signale
     * une vraie corruption/altération, jamais un artefact de date.
     *
     * Un échec de DÉCHIFFREMENT (tag GCM invalide) est lui aussi enregistré
     * comme CORRUPTED : c'est un signal de falsification encore plus fort
     * qu'un simple écart de hash.
     */
    private void verifyAndSave(Document document)
    {
        if (document.getStatus() == DocumentStatus.CORRUPTED ||
            document.getStatus() == DocumentStatus.DELETED)
        {
            return;
        }

        byte[] encryptedBytes;
        try (InputStream fileStream = storageService.download(document.getStorageKey()))
        {
            if (fileStream == null)
            {
                saveResult(document, CheckResult.EMPTY, "Fichier introuvable dans le stockage");
                return;
            }
            encryptedBytes = fileStream.readAllBytes();
        }
        catch (Exception e)
        {
            log.warn("[Fixity] Impossible de télécharger {} ({}) : {}",
                document.getId(), document.getStorageKey(), e.getMessage());
            return;
        }

        byte[] decryptedBytes;
        try
        {
            decryptedBytes = documentEncryptionService.decrypt(encryptedBytes);
        }
        catch (CleChiffrementException e)
        {
            // Problème de clé, pas de fichier : ne rien conclure sur ce document (jamais CORRUPTED pour ça).
            log.error("[Fixity] Document {} non vérifié, clé de chiffrement en cause : {}", document.getId(), e.getMessage());
            alerteIntegriteService.cleChiffrementAnormale(e.getMessage());
            return;
        }
        catch (Exception e)
        {
            log.error("[Fixity] Déchiffrement échoué pour {} — contenu altéré ou clé invalide : {}",
                document.getId(), e.getMessage());
            saveResult(document, CheckResult.CORRUPTED,
                "Échec du déchiffrement — fichier altéré ou clé invalide");
            return;
        }

        // Le hash du fichier n'est plus comparé à la SEULE valeur de la base : une base réécrite donnerait un
        // faux "corrompu" (empreinte modifiée) ou un faux "intact" (fichier ET empreinte modifiés). L'arbitre
        // confronte le fichier à des preuves hors base — voir PreuveIntegriteService.
        String actualHash = hashService.calculateFromBytes(decryptedBytes);
        PreuveIntegriteService.Constat constat = preuveIntegriteService.evaluer(document, actualHash);

        switch (constat.verdict())
        {
            case OK ->
            {
                if (constat.degrade())
                {
                    log.warn("[Fixity] Document {} vérifié par simple comparaison d'empreinte — aucune preuve "
                        + "indépendante consultable (ancien document non signé ou clé de confiance absente)",
                        document.getId());
                }
                saveResult(document, CheckResult.OK, null);
            }
            case PREUVE_ALTEREE -> savePreuveAlteree(document, constat.raison());
            case FICHIER_ALTERE -> saveResult(document, CheckResult.CORRUPTED, constat.raison());
        }
    }

    /**
     * Le fichier est conforme à une preuve indépendante mais l'enregistrement en base ne l'est plus : ce n'est PAS
     * une corruption du document (il reste ACTIF, consultable, indexé) mais un signe d'intrusion dans la base —
     * alerte réservée à l'administration. Une alerte par changement : un contrôle nocturne qui retrouve la même
     * anomalie ne renotifie pas.
     */
    private void savePreuveAlteree(Document document, String raison)
    {
        Optional<FixityCheckResult> existant = fixityCheckResultRepository.findByDocumentId(document.getId());
        boolean dejaSignale = existant.isPresent()
            && existant.get().getResult() == CheckResult.PREUVE_ALTEREE
            && raison.equals(existant.get().getRaison());

        FixityCheckResult resultat = existant.orElseGet(() ->
        {
            FixityCheckResult r = new FixityCheckResult();
            r.setDocument(document);
            return r;
        });
        resultat.setCheckedAt(Instant.now());
        resultat.setResult(CheckResult.PREUVE_ALTEREE);
        resultat.setRaison(raison);
        fixityCheckResultRepository.save(resultat);

        log.error("[Fixity] PREUVES ALTÉRÉES en base pour le document {} : {}", document.getId(), raison);
        if (!dejaSignale)
        {
            alerteIntegriteService.documentPreuveAlteree(document, raison);
        }
    }

    /**
     * Enregistre le résultat en base de données. CORRUPTED et EMPTY font tous les deux
     * basculer document.status vers CORRUPTED (voir Document.suppressionPrevueLe pour la
     * suite du cycle de vie) — EMPTY (fichier disparu du stockage) est un signal au moins
     * aussi grave qu'un écart de hash, il doit être traité pareil, pas ignoré.
     */
    @Transactional
    private void saveResult(Document document, CheckResult result, String raison)
    {
        try
        {
            Optional<FixityCheckResult> existing =
                fixityCheckResultRepository.findByDocumentId(document.getId());

            FixityCheckResult fixityCheckResult;

            if (existing.isPresent())
            {
                fixityCheckResult = existing.get();
            }
            else
            {
                fixityCheckResult = new FixityCheckResult();
                fixityCheckResult.setDocument(document);
            }

            fixityCheckResult.setCheckedAt(Instant.now());
            fixityCheckResult.setResult(result);
            fixityCheckResult.setRaison(raison);
            fixityCheckResultRepository.save(fixityCheckResult);

            if (result == CheckResult.CORRUPTED || result == CheckResult.EMPTY)
            {
                document.setStatus(DocumentStatus.CORRUPTED);
                documentRepository.save(document);

                // Retrait de l'index de recherche : "seulement accessible aux admin et
                // éditeur ayant autorité" doit aussi valoir pour la recherche plein texte,
                // sinon son titre resterait trouvable par n'importe qui. Best-effort — la
                // ligne DB reste la source de vérité, un échec ici n'a pas à bloquer le reste.
                try
                {
                    meilisearchService.deleteDocument(document.getId().toString());
                }
                catch (Exception e)
                {
                    log.warn("[Fixity] Best-effort : retrait Meilisearch échoué pour {} : {}",
                        document.getId(), e.getMessage());
                }

                notifyCorruption(document, raison);
            }
        }
        catch(Exception e)
        {
            throw new RuntimeException("Erreur lors de l'enregistrement du resultat", e);
        }
    }

    /**
     * Notifie TOUS les utilisateurs ayant accès au document (voir
     * DocumentService.getUtilisateursAyantAcces — tout rôle confondu, pas
     * seulement les éditeurs : un simple USER membre du groupe doit lui aussi
     * être prévenu), en plus des ADMIN_UO ayant autorité sur son UO et des
     * ADMIN globaux. Best-effort : un échec de notification ne doit jamais
     * faire échouer l'enregistrement du résultat de vérification lui-même.
     */
    private void notifyCorruption(Document document, String raison)
    {
        try
        {
            // LinkedHashMap plutôt que simple liste : dédoublonne par id (un
            // même utilisateur peut apparaître dans plusieurs des trois
            // sources ci-dessous) tout en gardant un ordre stable.
            java.util.Map<java.util.UUID, User> destinataires = new java.util.LinkedHashMap<>();

            documentService.getUtilisateursAyantAcces(document)
                .forEach(u -> destinataires.put(u.getId(), u));

            if (document.getUniteOrganisationnelle() != null)
            {
                uniteOrganisationnelleService
                    .getAdminUOAvecAutoriteSur(document.getUniteOrganisationnelle().getId())
                    .forEach(u -> destinataires.put(u.getId(), u));
            }

            userRepository.findByRoleName(Role_Name.ADMIN)
                .forEach(u -> destinataires.put(u.getId(), u));

            notificationService.notifier(new ArrayList<>(destinataires.values()), NotificationType.DOCUMENT_CORROMPU,
                "Le document \"" + document.getTitre() + "\" a été détecté comme corrompu "
                + "lors de la vérification d'intégrité de routine (" + raison + "). "
                + "Il n'est désormais visible que des administrateurs ayant autorité sur son UO "
                + "et des éditeurs y ayant accès — l'un d'eux peut le supprimer ou le remplacer.");

            auditLogService.log(null, AuditAction.DOCUMENT_CORRUPTION_DETECTEE, AuditCible.DOCUMENT,
                document.getId().toString(),
                document.getUniteOrganisationnelle() != null ? document.getUniteOrganisationnelle().getId() : null,
                "Corruption détectée sur le document \"" + document.getTitre() + "\" — " + raison,
                false);
        }
        catch (Exception e)
        {
            log.warn("[Fixity] Notification (best-effort) échouée pour {} : {}",
                document.getId(), e.getMessage());
        }
    }
}