package made.archive.service.document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.DocumentUploadResultDto;
import made.archive.dto.FinalizeUploadRequestDto;
import made.archive.dto.UniteOrganisationnelleDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.DataType;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.GroupeAccess;
import made.archive.entite.MetaData;
import made.archive.entite.NotificationType;
import made.archive.entite.PkiKeyStatus;
import made.archive.entite.Projet;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeAccess;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DataTypeRepository;
import made.archive.repository.DocumentRepository;
import made.archive.repository.GroupeAccessRepository;
import made.archive.repository.ProjetRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UserRepository;
import made.archive.security.DocumentEncryptionService;
import made.archive.security.HsmKeyStoreService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.notification.NotificationService;
import made.archive.service.organisation.UniteOrganisationnelleService;
import made.archive.service.storage.StorageService;
import made.archive.util.DocumentVersionLabels;

/**
 * Note sur les frontières transactionnelles (finalizeUpload) :
 * la méthode N'EST PAS @Transactional dans son ensemble — seule la section
 * qui écrit réellement en base (GroupeAccess + Document + DataType, un tout
 * cohérent) est enveloppée dans une transaction COURTE via TransactionTemplate.
 * L'upload MinIO, la signature HSM, la génération de regex (Ollama) et
 * l'indexation Meilisearch tournent EN DEHORS de toute transaction : ce sont
 * des I/O externes lentes qui n'ont rien à gagner à immobiliser une connexion
 * DB du pool pendant leur exécution, et leur échec ne doit pas nécessiter de
 * rollback (le document archivé reste valide même si, par ex., l'indexation
 * Meilisearch échoue).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadeService
{
    private final StorageService                  storageService;
    private final OcrService                      ocrService;
    private final MeilisearchService               meilisearchService;
    private final DocumentRepository               documentRepository;
    private final TypeDocumentRepository           typeDocumentRepository;
    private final UserRepository                   userRepository;
    private final GroupeAccessRepository           groupeAccessRepository;
    private final DataTypeRepository               dataTypeRepository;
    private final OcrSessionCache                  ocrSessionCache;
    private final HsmKeyStoreService               hsmKeyStoreService;
    private final UniteOrganisationnelleService    uniteOrganisationnelleService;
    private final DocumentEncryptionService        documentEncryptionService;
    private final OllamaService                    ollamaService;       // ✅ Ajouté pour Phase 2
    private final ProjetRepository                 projetRepository;
    private final NotificationService              notificationService;
    private final PlatformTransactionManager       transactionManager;
    private final AuditLogService                  auditLogService;
    private final DocumentRetentionService         documentRetentionService;
    private final made.archive.service.organisation.PhysicalLocationService physicalLocationService;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void initTransactionTemplate()
    {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public DocumentUploadResultDto finalizeUpload(FinalizeUploadRequestDto request)
    {
        UUID sessionId;
        try
        {
            sessionId = UUID.fromString(request.getSessionId());
        }
        catch (IllegalArgumentException e)
        {
            throw new BusinessException("Session ID invalide");
        }

        OcrSessionCache.OcrSessionData sessionData = ocrSessionCache.getSession(sessionId);
        if (sessionData == null)
        {
            throw new BusinessException(
                "Session expirée ou inexistante. Veuillez re-déposer le fichier.");
        }

        try
        {
            // ── 1. Entités liées ──────────────────────────────────────────────
            TypeDocument typeDocument = typeDocumentRepository
                .findByIdWithMetaData(sessionData.typeDocumentId)
                .orElseThrow(() -> new BusinessException("Type de document introuvable"));

            User uploadedBy = userRepository
                .findById(request.getDocumentUploadDto().getUploadedById())
                .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));

            // ── 1b. UO de l'éditeur — scope de la détection de doublons ────────
            UniteOrganisationnelle uo =
                uniteOrganisationnelleService.getUOActuelleEntite(uploadedBy.getId());

            // ── 1c. Projet (optionnel) — rattachement à un dossier/affaire ─────
            Long projetId = request.getDocumentUploadDto().getProjetId();
            final Projet projetDemande = projetId == null
                ? null
                : projetRepository.findById(projetId)
                    .orElseThrow(() -> new BusinessException("Projet introuvable : " + projetId));

            // ── 1d. Version précédente (optionnelle) — chaînage v1 → v2 → ... ──
            // Chaîne strictement LINÉAIRE : on ne peut versionner que la version
            // ACTUELLE d'une chaîne (derniereVersion == true), jamais une version
            // déjà remplacée — évite les branches.
            UUID documentPrecedentId = request.getDocumentUploadDto().getDocumentPrecedentId();
            final Document documentPrecedent = documentPrecedentId == null
                ? null
                : documentRepository.findById(documentPrecedentId)
                    .orElseThrow(() -> new BusinessException(
                        "Document précédent introuvable : " + documentPrecedentId));

            if (documentPrecedent != null)
            {
                if (!documentPrecedent.isDerniereVersion())
                {
                    throw new BusinessException(
                        "Ce document a déjà été remplacé par une version plus récente — "
                        + "repartez de la dernière version de la chaîne");
                }
                if (documentPrecedent.getUniteOrganisationnelle() == null
                    || !documentPrecedent.getUniteOrganisationnelle().getId().equals(uo.getId()))
                {
                    throw new BusinessException(
                        "Le document précédent n'appartient pas à votre unité organisationnelle");
                }
                if (!documentPrecedent.getTypeDocument().getId().equals(typeDocument.getId()))
                {
                    throw new BusinessException(
                        "Une nouvelle version doit être du même type de document que la précédente");
                }
            }

            final long nouvelleVersion = documentPrecedent != null
                ? documentPrecedent.getVersion() + 1
                : 1L;
            final Document racineVersion = documentPrecedent == null
                ? null
                : (documentPrecedent.getDocumentRacine() != null
                    ? documentPrecedent.getDocumentRacine()
                    : documentPrecedent);

            // Le projet est hérité du prédécesseur si non explicitement
            // redéfini pour cette version — garde la continuité du dossier.
            final Projet projet = projetDemande != null
                ? projetDemande
                : (documentPrecedent != null ? documentPrecedent.getProjet() : null);

            // ── 2. Unicité basée sur le fichier SOURCE, scopée par UO ──────────
            // Deux UO différentes peuvent archiver le même fichier sans conflit ;
            // la même UO ne peut l'archiver qu'une fois, quel que soit le type
            // de document choisi.
            if (documentRepository.existsByOriginalSha256AndUniteOrganisationnelle_Id(
                    sessionData.originalSha256, uo.getId()))
            {
                throw new BusinessException(
                    "Ce document existe déjà en archive pour votre unité organisationnelle");
            }

            // ── 3. Validation métadonnées ─────────────────────────────────────
            List<DataType> dataTypesToSave = validateAndBuildDataTypes(
                request.getMetaDataValidated(), typeDocument);

            // ── 3b. Éligibilité PKI — vérifiée tôt, avant tout I/O coûteux ─────
            if (uploadedBy.getPkiKeyStatus() != PkiKeyStatus.ACTIVE
                || uploadedBy.getPkiKeyAlias() == null || uploadedBy.getPkiKeyAlias().isBlank())
            {
                throw new BusinessException(
                    "Vous ne possédez pas de clé de signature PKI active. "
                    + "Contactez un administrateur.");
            }

            // ── 4. PKI : signature avec la clé de l'éditeur qui dépose ─────────
            // Plus de génération de clé éphémère : on signe avec la clé privée
            // PERSISTANTE de l'éditeur, gardée dans le HSM fichier. La clé ne
            // sort jamais du HSM ; seule la signature (hex) revient ici. Fait
            // AVANT l'upload MinIO : si la signature échoue, rien n'a encore
            // été écrit sur le stockage, donc rien à nettoyer.
            String signature;
            try
            {
                signature = hsmKeyStoreService.sign(
                    uploadedBy.getPkiKeyAlias(), sessionData.pdfaSha256);
                log.info("[PKI] Document signé avec la clé de l'éditeur {}", uploadedBy.getId());
            }
            catch (Exception e)
            {
                log.error("[PKI] Erreur de signature : {}", e.getMessage());
                throw new BusinessException("Impossible de signer le document", e);
            }

            // ── 5. Chiffrement (dernière étape avant MinIO) + Upload PDF/A ─────
            // pdfaSha256 et la signature PKI (ci-dessus) portent sur
            // sessionData.pdfABytes EN CLAIR — le chiffrement n'intervient
            // qu'ici, juste avant l'écriture, et ne doit jamais influencer
            // ces valeurs.
            byte[] encryptedPdfA = documentEncryptionService.encrypt(sessionData.pdfABytes);
            // Nom de fichier avec extension .pdf, pas celle du fichier ORIGINAL (.odt,
            // .docx...) : les octets écrits sont le PDF/A converti (+ chiffré), jamais le
            // fichier d'origine — le garder aurait affiché un ".odt" trompeur dans MinIO
            // pour un objet qui n'a plus rien d'un document LibreOffice.
            String nomSansExtension = sessionData.originalFilename.contains(".")
                ? sessionData.originalFilename.substring(0, sessionData.originalFilename.lastIndexOf('.'))
                : sessionData.originalFilename;
            // Collision de clé quasi impossible (UUID v4 : ~5,3×10^36 valeurs) mais
            // un PUT MinIO sur une clé existante écraserait silencieusement le fichier
            // d'un AUTRE document sans la moindre erreur — donc on vérifie avant
            // d'écrire plutôt que de faire confiance à la seule probabilité. La boucle
            // ne tourne concrètement jamais plus d'une fois ; elle transforme un
            // écrasement silencieux (indétectable) en un cas géré explicitement.
            String candidateKey;
            int tentative = 0;
            do
            {
                if (tentative++ > 5)
                {
                    throw new BusinessException(
                        "Impossible de générer une clé de stockage MinIO libre après " + tentative + " tentatives");
                }
                candidateKey = "pdfa/" + typeDocument.getNom() + "/" + LocalDate.now() + "/"
                    + UUID.randomUUID() + "/" + nomSansExtension + ".pdf";
            }
            while (storageService.exists(candidateKey));

            String pdfAStorageKey = storageService.uploadBytes(
                encryptedPdfA, candidateKey, "application/octet-stream");
            log.info("[Upload-Phase2] PDF/A chiffré et stocké : {}", pdfAStorageKey);

            // ── 6. Rétention ──────────────────────────────────────────────────
            // (le fichier original n'est PAS conservé dans MinIO — seul son
            // SHA-256, sessionData.originalSha256, est gardé pour la détection
            // de doublons ; seul le PDF/A archivé occupe du stockage)
            //
            // Remplacement d'un document CORROMPU : on reprend l'échéance de
            // rétention du prédécesseur telle quelle, jamais recalculée — le
            // remplacement corrige le contenu, pas les droits d'archivage déjà
            // acquis. Si elle était nulle (aucune limite), elle le reste.
            LocalDate retentionUntil;
            if (documentPrecedent != null && documentPrecedent.getStatus() == DocumentStatus.CORRUPTED)
            {
                retentionUntil = documentPrecedent.getRetentionUntil();
            }
            else
            {
                Long anneesRetention = typeDocument.getRetention().getRetentionYears();
                retentionUntil = anneesRetention != null
                    ? LocalDate.now().plusYears(anneesRetention)
                    : null;
            }

            // ── 7-10. Écritures DB (GroupeAccess + Document + DataType) ────────
            // Regroupées dans UNE transaction COURTE, ouverte seulement ici —
            // pas de connexion DB immobilisée pendant l'upload MinIO ni la
            // signature HSM ci-dessus (déjà faits, hors transaction).
            //
            // Fenêtre de course possible : deux éditeurs de la MÊME UO peuvent
            // passer le check anti-doublon (étape 2) tous les deux avant que
            // l'un des deux n'ait encore sauvegardé — le check applicatif seul
            // ne peut pas l'empêcher. C'est la contrainte unique DB
            // (uk_document_original_sha256_uo) qui tranche, atomiquement, en
            // rejetant le second save.
            final Document savedDocument;
            try
            {
                savedDocument = transactionTemplate.execute(status -> {
                    // Le projet (s'il existe) a été résolu HORS transaction plus haut —
                    // ré-attaché ici pour pouvoir lire en sécurité ses relations LAZY
                    // (access, groupe) dans la session Hibernate active de cette
                    // transaction, sans LazyInitializationException.
                    Projet projetGere = projet != null
                        ? projetRepository.findById(projet.getId()).orElse(null)
                        : null;

                    GroupeAccess groupe;
                    TypeAccess accessFinal;

                    if (projetGere != null && projetGere.getAccess() == TypeAccess.PRIVE)
                    {
                        // Le projet prime sur le choix de l'éditeur : le document
                        // hérite automatiquement de sa confidentialité et PARTAGE le
                        // même GroupeAccess que le projet — jamais un groupe recréé
                        // par document (voir ProjetService, où ce groupe n'est créé
                        // qu'une seule fois, à la création du projet).
                        accessFinal = TypeAccess.PRIVE;
                        groupe = projetGere.getGroupe();
                    }
                    else
                    {
                        accessFinal = request.getDocumentUploadDto().getAccess();
                        groupe = null;
                        if (accessFinal == TypeAccess.PRIVE)
                        {
                            String groupeNom = request.getDocumentUploadDto().getGroupeNom();
                            if (groupeNom == null || groupeNom.isBlank())
                            {
                                throw new BusinessException(
                                    "Le nom du groupe est obligatoire pour un document privé");
                            }

                            GroupeAccess g = new GroupeAccess();
                            g.setNom(groupeNom);
                            g.setCreateAt(LocalDate.now());

                            List<User> membres = new ArrayList<>();
                            membres.add(uploadedBy);

                            if (request.getDocumentUploadDto().getGroupeMembresIds() != null
                                && !request.getDocumentUploadDto().getGroupeMembresIds().isEmpty())
                            {
                                List<User> autres = userRepository.findAllById(
                                    request.getDocumentUploadDto().getGroupeMembresIds().stream()
                                        .filter(id -> !id.equals(uploadedBy.getId()))
                                        .toList()
                                );
                                membres.addAll(autres);
                            }

                            g.setMembres(membres);
                            groupe = groupeAccessRepository.save(g);
                        }
                    }

                    Document document = new Document();
                    document.setTitre(request.getDocumentUploadDto().getTitre());
                    document.setAccess(accessFinal);
                    document.setPdfaSha256(sessionData.pdfaSha256);
                    document.setOriginalSha256(sessionData.originalSha256);
                    document.setStorageKey(pdfAStorageKey);
                    document.setPkiSignature(signature);
                    document.setRetentionUntil(retentionUntil);
                    document.setCreateAt(LocalDateTime.now());
                    document.setVersion(nouvelleVersion);
                    document.setDocumentPrecedent(documentPrecedent);
                    document.setDocumentRacine(racineVersion);
                    document.setDerniereVersion(true);
                    document.setStatus(DocumentStatus.ACTIVE);
                    document.setIntegrityLevel(request.getDocumentUploadDto().getIntegrityLevel());
                    document.setTypeDocument(typeDocument);
                    document.setUploadedBy(uploadedBy);
                    document.setGroupe(groupe);
                    document.setUniteOrganisationnelle(uo);
                    document.setProjet(projet);

                    UUID physicalLocationId = request.getDocumentUploadDto().getPhysicalLocationId();
                    if (physicalLocationId != null)
                    {
                        document.setPhysicalLocation(
                            physicalLocationService.resolvePourRattachement(physicalLocationId, document));
                    }

                    // saveAndFlush (pas save) : force l'INSERT à partir
                    // immédiatement, pour que la violation de contrainte,
                    // s'il y en a une, remonte ICI et pas plus tard, hors de
                    // ce try/catch.
                    Document saved = documentRepository.saveAndFlush(document);

                    // Le prédécesseur n'est plus la version actuelle — bascule
                    // atomique avec la création de cette nouvelle version.
                    if (documentPrecedent != null)
                    {
                        documentPrecedent.setDerniereVersion(false);
                        documentRepository.save(documentPrecedent);
                    }

                    dataTypesToSave.forEach(dt -> dt.setDocument(saved));
                    dataTypeRepository.saveAll(dataTypesToSave);

                    return saved;
                });
            }
            catch (DataIntegrityViolationException e)
            {
                cleanupOrphanedUpload(pdfAStorageKey);

                // Toute violation de contrainte DB atterrissait ici avec le même message
                // "document existe déjà" — y compris des violations SANS AUCUN RAPPORT avec
                // un doublon (ex. "value too long for type character varying(50)" sur titre),
                // ce qui a fait perdre un temps fou à diagnostiquer un faux "doublon" alors
                // que la vraie cause était ailleurs. On ne distingue le VRAI cas doublon
                // (contrainte uk_document_original_sha256_uo) que par son nom dans le message
                // Postgres — tout le reste remonte tel quel, honnêtement.
                Throwable racine = e;
                while (racine.getCause() != null && racine.getCause() != racine) racine = racine.getCause();
                String messageRacine = racine.getMessage() != null ? racine.getMessage() : "";

                if (messageRacine.contains("uk_document_original_sha256_uo"))
                {
                    log.warn("[Upload-Phase2] Course anti-doublon perdue pour {} (UO {}) : {}",
                        sessionData.originalSha256, uo.getId(), messageRacine);
                    throw new BusinessException(
                        "Ce document existe déjà en archive pour votre unité organisationnelle");
                }

                log.error("[Upload-Phase2] Violation de contrainte (pas un doublon) pour {} (UO {}) : {}",
                    sessionData.originalSha256, uo.getId(), messageRacine, e);
                throw new BusinessException(
                    "Erreur lors de l'enregistrement du document : " + messageRacine);
            }
            catch (BusinessException e)
            {
                cleanupOrphanedUpload(pdfAStorageKey);
                throw e;
            }
            catch (Exception e)
            {
                log.error("[Upload-Phase2] Échec de l'enregistrement en base : {}", e.getMessage(), e);
                cleanupOrphanedUpload(pdfAStorageKey);
                throw new BusinessException("Erreur lors de l'enregistrement du document", e);
            }
            log.info("[Upload-Phase2] Document créé : {} ({} métadonnée(s))",
                savedDocument.getId(), dataTypesToSave.size());

            // ── 10b. Notification "document ajouté" ─────────────────────────────
            // PUBLIC → tous les membres de l'UO ; PRIVE → seulement les membres
            // du groupe d'accès (pas toute l'UO, sinon on fuite l'existence d'un
            // document confidentiel à des gens qui n'y ont pas accès). Dans les
            // deux cas, les ADMIN globaux sont notifiés aussi. Best-effort, hors
            // transaction, comme les autres étapes ci-dessous.
            try
            {
                notifyDocumentAjoute(savedDocument);
            }
            catch (Exception e)
            {
                log.warn("[Upload-Phase2] Notification (best-effort) échouée pour {} : {}",
                    savedDocument.getId(), e.getMessage());
            }

            // ── 11. GÉNÉRATION REGEX si premier document du type ────────────────
            // Hors transaction (appel Ollama, potentiellement lent) et
            // best-effort : un échec ici ne doit jamais faire perdre le
            // document déjà archivé et signé avec succès juste au-dessus.
            //
            // La combinaison "texte OCR + valeur connue" permet à Qwen de localiser
            // précisément le pattern au lieu d'inférer un pattern générique.
            try
            {
                generateRegexIfFirstDocument(
                    savedDocument,
                    sessionData,
                    request.getMetaDataValidated());
            }
            catch (Exception e)
            {
                log.warn("[Upload-Phase2] Génération regex (best-effort) échouée pour le type {} : {}",
                    typeDocument.getId(), e.getMessage());
            }

            // ── 12. Enregistrement OCR + Meilisearch ────────────────────────────
            // sessionData.extractedText est DÉJÀ calculé depuis la Phase 1
            // (ocr-preview) — on l'enregistre tel quel plutôt que de
            // retélécharger/déchiffrer/re-OCRiser le PDF/A pour rien. Hors
            // transaction et best-effort, même raisonnement qu'au-dessus.
            String extractedText = sessionData.extractedText;
            try
            {
                extractedText = ocrService.recordExtractedText(savedDocument, sessionData.extractedText);
            }
            catch (Exception e)
            {
                log.warn("[Upload-Phase2] Enregistrement OCR (best-effort) échoué pour {} : {}",
                    savedDocument.getId(), e.getMessage());
            }

            boolean hasExtractedText = extractedText != null && !extractedText.isBlank();
            if (hasExtractedText || !dataTypesToSave.isEmpty())
            {
                meilisearchService.indexDocument(
                    savedDocument,
                    hasExtractedText ? extractedText : null,
                    dataTypesToSave);
            }

            // ── 13. Nettoyage session ──────────────────────────────────────────
            ocrSessionCache.deleteSession(sessionId);

            auditLogService.log(uploadedBy,
                documentPrecedent != null ? AuditAction.DOCUMENT_NOUVELLE_VERSION : AuditAction.DOCUMENT_UPLOAD_REUSSI,
                AuditCible.DOCUMENT, savedDocument.getId().toString(), uo.getId(),
                (documentPrecedent != null
                    ? "Nouvelle version (v" + nouvelleVersion + ") de " + typeDocument.getNom()
                    : "Upload de " + typeDocument.getNom())
                    + " par " + uploadedBy.getEmail(),
                true);

            // ── 14. Résultat ───────────────────────────────────────────────────
            return DocumentUploadResultDto.builder()
                .documentId(savedDocument.getId())
                .status(savedDocument.getStatus())
                .pdfaSha256(savedDocument.getPdfaSha256())
                .originalSha256(savedDocument.getOriginalSha256())
                .storageKey(savedDocument.getStorageKey())
                .version(savedDocument.getVersion())
                .versionLabel(DocumentVersionLabels.compute(savedDocument))
                .metaDataSuggestions(new LinkedHashMap<>())
                .build();
        }
        catch (BusinessException e)
        {
            logEchecUpload(request, e.getMessage());
            throw e;
        }
        catch (Exception e)
        {
            log.error("[Upload-Phase2] Erreur : {}", e.getMessage(), e);
            logEchecUpload(request, e.getMessage());
            throw new BusinessException(
                "Erreur lors de la finalisation : " + e.getMessage(), e);
        }
    }

    /**
     * Journalise un échec d'upload — best-effort et blindé indépendamment de
     * AuditLogService.log() : on est déjà dans un chemin d'erreur, la journalisation
     * ne doit surtout pas masquer l'exception métier réelle en train d'être relancée.
     */
    private void logEchecUpload(FinalizeUploadRequestDto request, String raison)
    {
        try
        {
            UUID uploadedById = request.getDocumentUploadDto() != null
                ? request.getDocumentUploadDto().getUploadedById() : null;
            User uploadedBy = uploadedById != null
                ? userRepository.findById(uploadedById).orElse(null) : null;

            // Sans ça, un échec d'upload reste invisible pour tout ADMIN_UO, même pour
            // un membre de sa propre UO (un IN SQL ne matche jamais une valeur NULL).
            Long uoId = uploadedBy != null
                ? uniteOrganisationnelleService.getUOActuelleUser(uploadedBy.getId())
                    .map(UniteOrganisationnelleDto::getId).orElse(null)
                : null;

            auditLogService.log(uploadedBy, AuditAction.DOCUMENT_UPLOAD_ECHOUE,
                AuditCible.DOCUMENT, null, uoId, "Échec d'upload : " + raison, false);
        }
        catch (Exception ignored)
        {
            // best-effort
        }
    }

    /**
     * Best-effort : supprime le PDF/A déjà uploadé sur MinIO quand
     * l'enregistrement en base échoue après coup (course anti-doublon
     * perdue, ou toute autre erreur pendant la transaction courte) — sinon
     * il reste orphelin, jamais référencé par aucun Document.
     */
    private void cleanupOrphanedUpload(String pdfAStorageKey)
    {
        try
        {
            // Garde-fou intégré (voir DocumentRetentionService) : puisque l'insert
            // Document a échoué, cette clé n'est référencée par aucune ligne — la
            // suppression procède normalement. Si jamais elle l'était (ne devrait
            // jamais arriver ici), elle serait refusée plutôt que d'effacer un
            // fichier encore utile.
            documentRetentionService.supprimerFichierMinioSiOrphelin(
                pdfAStorageKey, "[Upload-Phase2] nettoyage après échec d'enregistrement");
        }
        catch (Exception cleanupError)
        {
            log.warn("[Upload-Phase2] Nettoyage MinIO orphelin échoué pour {} : {}",
                pdfAStorageKey, cleanupError.getMessage());
        }
    }

    /**
     * PUBLIC : notifie tous les membres de l'UO propriétaire.
     * PRIVE  : notifie seulement les membres du groupe d'accès (les seuls à
     *          pouvoir voir ce document) — jamais toute l'UO.
     * Dans les deux cas, les ADMIN globaux sont notifiés en plus.
     */
    private void notifyDocumentAjoute(Document document)
    {
        List<User> destinataires = new ArrayList<>();

        if (document.getAccess() == TypeAccess.PRIVE)
        {
            if (document.getGroupe() != null && document.getGroupe().getMembres() != null)
            {
                destinataires.addAll(document.getGroupe().getMembres());
            }
        }
        else
        {
            destinataires.addAll(
                userRepository.findByUniteOrganisationnelleId(document.getUniteOrganisationnelle().getId()));
        }

        destinataires.addAll(userRepository.findByRoleName(Role_Name.ADMIN));

        notificationService.notifier(destinataires, NotificationType.DOCUMENT_AJOUTE,
            "Un nouveau document \"" + document.getTitre() + "\" a été ajouté dans l'unité organisationnelle \""
                + document.getUniteOrganisationnelle().getNom() + "\".");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Génération regex — Phase 2
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ✅ Déclenche la génération des regex si c'est le PREMIER document du type.
     *
     * Appelé après la sauvegarde des DataTypes (étape 12), ce qui garantit que
     * les valeurs saisies par l'utilisateur sont disponibles pour enrichir le prompt.
     *
     * Logique :
     *   1. Vérifier si c'est le premier document avec succès OCR pour ce type
     *      (countSuccess == 0 car l'OcrResult n'est pas encore créé à ce stade,
     *       il le sera à l'étape 13 via ocrService.processDocument)
     *   2. Récupérer les MetaData sans regex
     *   3. Construire la map nom → valeur saisie
     *   4. Appeler OllamaService avec texte OCR + valeurs saisies
     *   5. Persister les regex générées
     *
     * @param savedDocument      Document nouvellement créé
     * @param sessionData        Données de session Phase 1 (contient extractedText)
     * @param metaDataValidated  Valeurs saisies par l'utilisateur en Phase 2
     */
    // ================================================================================
    // DocumentUploadeService.generateRegexIfFirstDocument() - UTILISER la Map
    // ================================================================================
    
    /**
     * ✅ Déclenche la génération des regex si c'est le PREMIER usage du type.
     * 
     * CHANGEMENT : Récupère la Map retournée par generateRegexForMetaData()
     * et la stocke dans TypeDocument.extractionRegexJson
     */
    private void generateRegexIfFirstDocument(
        Document savedDocument,
        OcrSessionCache.OcrSessionData sessionData,
        List<FinalizeUploadRequestDto.MetaDataValueDto> metaDataValidated)
    {
        String extractedText = sessionData.extractedText;
        if (extractedText == null || extractedText.isBlank())
        {
            log.debug("[Regex-Phase2] Pas de texte OCR disponible → génération regex ignorée");
            return;
        }
    
        TypeDocument typeDocument = savedDocument.getTypeDocument();
        
        // ── NOUVELLE LOGIQUE : Vérifier le flag regexGenerated ────────────────
        if (typeDocument.hasRegexGenerated())
        {
            log.debug("[Regex-Phase2] Type {} : regex déjà générées, réutilisation",
                      typeDocument.getId());
            return;
        }
    
        List<MetaData> metaDataList = typeDocument.getMetaData();
        if (metaDataList == null || metaDataList.isEmpty())
        {
            log.debug("[Regex-Phase2] Aucune métadonnée définie pour le type");
            return;
        }
    
        // ── Construire la map de TOUTES les valeurs saisies ────────────────
        Map<String, String> fieldValues = metaDataValidated.stream()
            .filter(dto -> dto.getValeur() != null && !dto.getValeur().isBlank())
            .collect(Collectors.toMap(
                FinalizeUploadRequestDto.MetaDataValueDto::getNom,
                FinalizeUploadRequestDto.MetaDataValueDto::getValeur,
                (existing, replacement) -> existing
            ));
    
        log.info("[Regex-Phase2] PREMIÈRE UTILISATION du type {} → génération regex " +
                 "pour {} champ(s) avec {} valeur(s) de contexte",
                 typeDocument.getId(), metaDataList.size(), fieldValues.size());
    
        // ── NOUVEAU : Appeler Ollama et récupérer la Map ────────────────────
        Map<String, String> generatedRegex = ollamaService.generateRegexForMetaData(
            metaDataList,
            extractedText,
            fieldValues);  // ← Retourne une Map au lieu de modifier MetaData
    
        // ── Stocker les regex dans TypeDocument ──────────────────────────────
        typeDocument.setExtractionRegexMap(generatedRegex);
        typeDocument.setRegexGenerated(true);
        typeDocumentRepository.save(typeDocument);
    
        log.info("[Regex-Phase2] ✅ {} regex générées et stockées dans TypeDocument {}",
                 generatedRegex.size(), typeDocument.getId());
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<DataType> validateAndBuildDataTypes(
        List<FinalizeUploadRequestDto.MetaDataValueDto> metaDataValidated,
        TypeDocument typeDocument)
    {
        List<DataType>  dataTypesToSave  = new ArrayList<>();
        List<String>    validationErrors = new ArrayList<>();

        Map<String, MetaData> expectedMetaDataMap = typeDocument.getMetaData().stream()
            .collect(Collectors.toMap(MetaData::getNom, m -> m));

        for (FinalizeUploadRequestDto.MetaDataValueDto metaDto : metaDataValidated)
        {
            try
            {
                MetaData expectedMeta = expectedMetaDataMap.get(metaDto.getNom());
                if (expectedMeta == null)
                {
                    validationErrors.add("Champ introuvable : " + metaDto.getNom());
                    continue;
                }

                String valeur = metaDto.getValeur();

                if (expectedMeta.getObligatoire() && (valeur == null || valeur.isBlank()))
                {
                    validationErrors.add(
                        "Le champ '" + expectedMeta.getNom() + "' est obligatoire");
                    continue;
                }

                DataType dataType = new DataType();
                dataType.setValeur(valeur);
                dataType.setMetaData(expectedMeta);
                dataTypesToSave.add(dataType);
            }
            catch (Exception e)
            {
                log.error("[Validation] Erreur inattendue pour {} : {}",
                          metaDto.getNom(), e.getMessage());
                validationErrors.add("Erreur technique pour " + metaDto.getNom());
            }
        }

        if (!validationErrors.isEmpty())
        {
            String msg = String.join(" | ", validationErrors);
            log.warn("[Validation] {} erreur(s) : {}", validationErrors.size(), msg);
            throw new BusinessException("Validation des métadonnées échouée : " + msg);
        }

        log.info("[Validation] Toutes les metadonnees sont valides");
        return dataTypesToSave;
    }
}