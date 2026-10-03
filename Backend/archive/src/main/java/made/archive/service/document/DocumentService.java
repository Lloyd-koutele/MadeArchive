package made.archive.service.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.MeilisearchProperties;
import made.archive.dto.ChangerAccesRequestDto;
import made.archive.dto.DocumentDetailDto;
import made.archive.dto.DocumentFolderDto;
import made.archive.dto.DocumentListItemDto;
import made.archive.dto.DocumentPageDto;
import made.archive.dto.DocumentVersionDto;
import made.archive.dto.DataTypeDto;
import made.archive.dto.FusionGroupeCheckDto;
import made.archive.entite.Attestation;
import made.archive.entite.GroupeAccess;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.DataType;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.FixityCheckResult;
import made.archive.entite.MetaData;
import made.archive.entite.MotifSuppression;
import made.archive.entite.Dossier;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeAccess;
import made.archive.entite.TypeDocument;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.FixityCheckResultRepository;
import made.archive.repository.DossierRepository;
import made.archive.repository.UserRepository;
import made.archive.security.DocumentEncryptionService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;
import made.archive.service.storage.StorageService;
import made.archive.util.DocumentVersionLabels;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.persistence.criteria.Predicate;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service unique pour la récupération, lecture et téléchargement des documents.
 *
 * Responsabilités :
 *   - Récupérer les dossiers (types) avec compteurs depuis la BD
 *   - Récupérer les documents d'un type avec pagination depuis la BD
 *   - Recherche hybride : Meilisearch (IDs) → BD (données complètes)
 *   - Streamer le PDF/A pour visualisation inline
 *   - Streamer le PDF/A pour téléchargement
 *   - Streamer le fichier original pour téléchargement
 *
 * Principe : la BD est la source de vérité. Meilisearch n'intervient
 * que pour la recherche full-text et retourne uniquement des IDs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService
{
    private final DocumentRepository        documentRepository;
    private final UserRepository            userRepository;
    private final StorageService            storageService;
    private final WebClient.Builder         webClientBuilder;
    private final MeilisearchProperties     meilisearchProperties;
    private final ObjectMapper              objectMapper;
    private final DocumentEncryptionService documentEncryptionService;
    private final AuditLogService           auditLogService;
    private final UniteOrganisationnelleService uniteOrganisationnelleService;
    private final FixityCheckResultRepository fixityCheckResultRepository;
    private final made.archive.service.organisation.PhysicalLocationService physicalLocationService;
    private final made.archive.service.organisation.DossierService          dossierService;
    private final made.archive.repository.DataTypeRepository dataTypeRepository;
    private final TypeDocumentService typeDocumentService;
    private final DossierRepository dossierRepository;
    private final made.archive.repository.GroupeAccessRepository groupeAccessRepository;
    private final MeilisearchService meilisearchService;
    private final made.archive.repository.AttestationRepository attestationRepository;
    private final DocumentRetentionService documentRetentionService;
    private final made.archive.repository.PlanClassementNoeudRepository planClassementNoeudRepository;

    private static final String INDEX_NAME        = "documents";

    
    private static final List<DocumentStatus> STATUTS_EXCLUS_LECTURE =
        List.of(DocumentStatus.DELETED, DocumentStatus.CORBEILLE);

    /** Délai de grâce PAR DÉFAUT de la corbeille — celui d'un type qui n'a pas défini le sien (voir delaiGraceJours). */
    public static final long DELAI_GRACE_CORBEILLE_JOURS = 6;

    /**
     * Délai de grâce (jours) avant la suppression définitive d'un document de ce type : la valeur saisie
     * à la création du type (Retention.periodGrace), sinon DELAI_GRACE_CORBEILLE_JOURS. Calculé à l'entrée
     * en corbeille et figé dans Document.suppressionPrevueLe : modifier le délai du type ne déplace pas
     * l'échéance des documents déjà en corbeille.
     */
    public static long delaiGraceJours(TypeDocument type)
    {
        Long saisi = type != null && type.getRetention() != null ? type.getRetention().getPeriodGrace() : null;
        return saisi != null && saisi > 0 ? saisi : DELAI_GRACE_CORBEILLE_JOURS;
    }

    // 1. DOSSIERS — grille de types avec compteurs
    
    @Transactional(readOnly = true)
    public List<DocumentFolderDto> getMesFolders(UserDetails userDetails)
    {
        User user = resolveUser(userDetails);

        // Requête groupée : type + count depuis la BD
        List<Object[]> rows = documentRepository
            .countDocumentsByTypeForUser(user.getId());

        return rows.stream()
            .map(row -> DocumentFolderDto.builder()
                .typeDocumentId((Long)   row[0])
                .typeDocumentNom((String) row[1])
                .count((Long)            row[2])
                .build())
            .collect(Collectors.toList());
    }

    // 2. LISTE — documents d'un type, paginés depuis la BD
    
    @Transactional(readOnly = true)
    public DocumentPageDto getMesDocumentsByType(
        Long typeDocumentId,
        int page,
        int size,
        LocalDate dateDebut,
        LocalDate dateFin,
        UserDetails userDetails)
    {
        User user = resolveUser(userDetails);

        Pageable pageable = PageRequest.of(
            Math.max(0, page - 1),
            Math.min(size, 50),
            Sort.by(Sort.Direction.DESC, "createAt")
        );

        Specification<Document> spec = (root, query, cb) ->
        {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("uploadedBy").get("id"), user.getId()));
            predicates.add(cb.equal(root.get("typeDocument").get("id"), typeDocumentId));
            predicates.add(root.get("status").in(STATUTS_EXCLUS_LECTURE).not());

            if (dateDebut != null)
            {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createAt"), dateDebut.atStartOfDay()));
            }
            if (dateFin != null)
            {
                predicates.add(cb.lessThanOrEqualTo(root.get("createAt"), dateFin.atTime(LocalTime.MAX)));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Document> pageResult = documentRepository.findAll(spec, pageable);

        List<DocumentListItemDto> items = pageResult.getContent().stream()
            .map(doc -> toListItemDto(doc, user))
            .collect(Collectors.toList());

        return DocumentPageDto.builder()
            .content(items)
            .page(page)
            .size(size)
            .totalElements(pageResult.getTotalElements())
            .totalPages(pageResult.getTotalPages())
            .build();
    }

    // 3. RECHERCHE HYBRIDE — Meilisearch (IDs) → BD (données)

    @Transactional(readOnly = true)
    public DocumentPageDto rechercher(
        String query,
        Long typeDocumentId,
        int page,
        int size,
        LocalDate dateDebut,
        LocalDate dateFin,
        UserDetails userDetails)
    {
        User user = resolveUser(userDetails);

        // Requête vide → liste BD directe
        if (query == null || query.isBlank())
        {
            if (typeDocumentId != null)
            {
                return getMesDocumentsByType(typeDocumentId, page, size, dateDebut, dateFin, userDetails);
            }
            return getTousMesDocuments(user, page, size);
        }

        // Recherche Meilisearch → IDs
        List<UUID> ids = searchMeilisearch(query, typeDocumentId, page, size);

        if (ids.isEmpty())
        {
            return DocumentPageDto.empty(page, size);
        }

        // BD : charger par IDs en filtrant sur l'utilisateur connecté (sécurité)
        List<Document> documents = documentRepository
            .findByIdInAndUploadedByIdAndStatusNotIn(
                ids, user.getId(), STATUTS_EXCLUS_LECTURE);

        LocalDateTime debut = dateDebut != null ? dateDebut.atStartOfDay()   : null;
        LocalDateTime fin   = dateFin   != null ? dateFin.atTime(LocalTime.MAX) : null;

        // Conserver l'ordre retourné par Meilisearch (pertinence)
        Map<UUID, Document> docMap = documents.stream()
            .filter(d -> debut == null || !d.getCreateAt().isBefore(debut))
            .filter(d -> fin   == null || !d.getCreateAt().isAfter(fin))
            .collect(Collectors.toMap(Document::getId, d -> d));

        List<DocumentListItemDto> items = ids.stream()
            .map(docMap::get)
            .filter(Objects::nonNull)
            .map(doc -> toListItemDto(doc, user))
            .collect(Collectors.toList());

        return DocumentPageDto.builder()
            .content(items)
            .page(page)
            .size(size)
            .totalElements(items.size())
            .totalPages(1)
            .build();
    }

    // 4. DÉTAIL — métadonnées complètes d'un document

    @Transactional(readOnly = true)
    public DocumentDetailDto getDetail(UUID documentId, UserDetails userDetails)
    {
        User user     = resolveUser(userDetails);
        Document doc  = resolveDocument(documentId, user);

        boolean estOuEtaitCorrompu = doc.getStatus() == DocumentStatus.CORRUPTED
            || doc.getStatutAvantCorbeille() == DocumentStatus.CORRUPTED;
        String corruptionRaison = estOuEtaitCorrompu
            ? fixityCheckResultRepository.findByDocumentId(doc.getId())
                .map(FixityCheckResult::getRaison).orElse(null)
            : null;

        return DocumentDetailDto.builder()
            .documentId(doc.getId())
            .titre(doc.getTitre())
            .typeDocumentId(doc.getTypeDocument().getId())
            .typeDocumentNom(doc.getTypeDocument().getNom())
            .status(doc.getStatus().name())
            .access(doc.getAccess().name())
            .integrityLevel(doc.getIntegrityLevel() != null
                ? doc.getIntegrityLevel().name() : null)
            .pdfaSha256(doc.getPdfaSha256())
            .originalSha256(doc.getOriginalSha256())
            .retentionUntil(doc.getRetentionUntil())
            .createAt(doc.getCreateAt())
            .version(doc.getVersion())
            .versionLabel(DocumentVersionLabels.compute(doc))
            .historiqueVersions(getHistoriqueVersions(doc))
            .corruptionRaison(corruptionRaison)
            .statutAvantCorbeille(doc.getStatutAvantCorbeille() != null ? doc.getStatutAvantCorbeille().name() : null)
            .suppressionPrevueLe(doc.getSuppressionPrevueLe())
            .activite(made.archive.service.organisation.PlanClassementService
                .chemin(made.archive.service.organisation.PlanClassementService.activiteEffective(doc)))
            .activiteNoeudId(made.archive.service.organisation.PlanClassementService.activiteEffective(doc) != null
                ? made.archive.service.organisation.PlanClassementService.activiteEffective(doc).getId() : null)
            .activiteSurDocument(doc.getPlanClassementNoeud() != null)
            .retentionYearsType(doc.getTypeDocument().getRetention() != null
                ? doc.getTypeDocument().getRetention().getRetentionYears() : null)
            .peutGererCorbeille(estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
                .anyMatch(u -> u.getId().equals(user.getId())))
            .metaData(doc.getData().stream()
                .map(dt -> DocumentDetailDto.MetaDataValueDto.builder()
                    .typeValeur(dt.getMetaData() != null ? dt.getMetaData().getNom() : null)
                    .valeur(dt.getValeur())
                    .build())
                .collect(Collectors.toList()))
            .physicalLocationId(doc.getPhysicalLocation() != null ? doc.getPhysicalLocation().getId() : null)
            .physicalLocationPath(doc.getPhysicalLocation() != null
                ? physicalLocationService.construireChemin(doc.getPhysicalLocation()) : null)
            .peutModifierEmplacement(estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
                .anyMatch(u -> u.getId().equals(user.getId())))
            .uniteOrganisationnelleId(doc.getUniteOrganisationnelle() != null
                ? doc.getUniteOrganisationnelle().getId() : null)
            .dossierId(doc.getDossier() != null ? doc.getDossier().getId() : null)
            .dossierNom(doc.getDossier() != null ? doc.getDossier().getNom() : null)
            .dossierCheminComplet(doc.getDossier() != null
                ? dossierService.construireChemin(doc.getDossier()) : null)
            .peutModifierDossier(estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
                .anyMatch(u -> u.getId().equals(user.getId())))
            .peutModifierAcces(estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
                .anyMatch(u -> u.getId().equals(user.getId()))
                && !(doc.getDossier() != null && doc.getDossier().getAccess() == TypeAccess.PRIVE))
            .build();
    }

    // 5. VISUALISATION — streamer le PDF/A inline (pour le lecteur PDF)
    
    @Transactional(readOnly = true)
    public byte[] streamPdfAForView(UUID documentId, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);

        auditLogService.log(user, AuditAction.DOCUMENT_CONSULTE, AuditCible.DOCUMENT,
            documentId.toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Consultation du document \"" + doc.getTitre() + "\" par " + user.getEmail(), true);

        return downloadFromStorage(doc.getStorageKey(), documentId, "PDF/A view");
    }

    // 5bis. MINIATURE (vues en grille) — générée et mise en cache une fois

    private static final String THUMBNAIL_PREFIX = "thumbnails/";
    private static final int    THUMBNAIL_WIDTH  = 320;

    @Transactional(readOnly = true)
    public byte[] getThumbnail(UUID documentId, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);

        String thumbnailKey = THUMBNAIL_PREFIX + documentId + ".jpg";

        if (storageService.exists(thumbnailKey))
        {
            try (InputStream stream = storageService.download(thumbnailKey))
            {
                return documentEncryptionService.decrypt(stream.readAllBytes());
            }
            catch (Exception e)
            {
                log.warn("[DocumentService] Miniature en cache illisible pour {}, régénération : {}",
                    documentId, e.getMessage());
            }
        }

        byte[] pdfBytes  = downloadFromStorage(doc.getStorageKey(), documentId, "génération miniature");
        byte[] thumbnail = rasterizePremierePage(pdfBytes, THUMBNAIL_WIDTH);

        try
        {
            storageService.uploadBytes(
                documentEncryptionService.encrypt(thumbnail), thumbnailKey, "image/jpeg");
        }
        catch (Exception e)
        {
            log.warn("[DocumentService] Échec mise en cache de la miniature pour {} : {}",
                documentId, e.getMessage());
        }

        return thumbnail;
    }

    private byte[] rasterizePremierePage(byte[] pdfBytes, int targetWidth)
    {
        try (org.apache.pdfbox.pdmodel.PDDocument pdf =
                 org.apache.pdfbox.pdmodel.PDDocument.load(pdfBytes))
        {
            org.apache.pdfbox.rendering.PDFRenderer renderer =
                new org.apache.pdfbox.rendering.PDFRenderer(pdf);
            org.apache.pdfbox.pdmodel.PDPage page = pdf.getPage(0);
            float largeurNaturelle = page.getMediaBox().getWidth();
            float scale = targetWidth / largeurNaturelle;

            java.awt.image.BufferedImage image = renderer.renderImage(0, scale);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(image, "jpg", out))
            {
                throw new IllegalStateException("Aucun encodeur JPEG disponible");
            }
            return out.toByteArray();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Impossible de générer la miniature : " + e.getMessage(), e);
        }
    }

    // 6. TÉLÉCHARGEMENT PDF/A — Content-Disposition: attachment

    @Transactional(readOnly = true)
    public byte[] downloadPdfA(UUID documentId, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);

        auditLogService.log(user, AuditAction.DOCUMENT_TELECHARGE, AuditCible.DOCUMENT,
            documentId.toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Téléchargement du document \"" + doc.getTitre() + "\" par " + user.getEmail(), true);

        return downloadFromStorage(doc.getStorageKey(), documentId, "PDF/A download");
    }

    /**
     * Retourne le nom de fichier suggéré pour le téléchargement du PDF/A.
     */
    @Transactional(readOnly = true)
    public String getPdfAFilename(UUID documentId, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);
        return sanitizeFilename(doc.getTitre()) + "_pdfa.pdf";
    }

    
    @Transactional(readOnly = true)
    public Document resolveDocumentPourAttestation(UUID documentId, UserDetails userDetails)
    {
        User user = resolveUser(userDetails);
        return resolveDocument(documentId, user);
    }

    @Transactional(readOnly = true)
    public byte[] lireBytesPdfAViaAttestation(Document doc)
    {
        return downloadFromStorage(doc.getStorageKey(), doc.getId(), "PDF/A via attestation publique");
    }

    @Transactional(readOnly = true)
    public Optional<Document> resolveDocumentSiVisible(UUID documentId, User user)
    {
        try
        {
            return Optional.of(resolveDocument(documentId, user));
        }
        catch (BusinessException e)
        {
            return Optional.empty();
        }
    }

    // 7. CORBEILLE — suppression volontaire (délai de grâce unique, voir DELAI_GRACE_CORBEILLE_JOURS, restaurable)

    /**
     * Supprime un document ACTIF (le met en corbeille) — motif obligatoire (ERREUR_ARCHIVAGE,
     * SUPPRESSION_LEGALE, ou AUTRE avec commentaire), conservé sur le document et dans le journal.
     * Purge automatique à l'échéance du délai de grâce du type, quel que soit son sort final : c'est
     * une décision volontaire de l'éditeur, restaurable d'ici là.
     *
     * @return la date de suppression définitive prévue.
     */
    @Transactional
    public LocalDate envoyerCorbeille(UUID documentId, MotifSuppression motif, String commentaire, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);

        boolean autorise = estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
            .anyMatch(u -> u.getId().equals(user.getId()));
        if (!autorise)
        {
            throw new BusinessException(
                "Seul un éditeur ayant accès à ce document peut l'envoyer à la corbeille");
        }

        DocumentRetentionService.validerMotifUtilisateur(motif, commentaire);

        if (doc.getTypeDocument().isSysteme())
        {
            throw new BusinessException("Ce document est géré par l'application (" + doc.getTypeDocument().getNom()
                + ") : il ne peut pas être supprimé");
        }

        if (doc.getStatus() == DocumentStatus.CORBEILLE)
        {
            throw new BusinessException("Ce document est déjà dans la corbeille, prévu pour le "
                + doc.getSuppressionPrevueLe());
        }
        if (doc.getStatus() == DocumentStatus.DELETED)
        {
            throw new BusinessException("Ce document est déjà supprimé définitivement");
        }

        DocumentStatus statutOrigine = doc.getStatus();
        String commentaireNet = commentaire != null && !commentaire.isBlank() ? commentaire.trim() : null;
        doc.setStatutAvantCorbeille(statutOrigine);
        doc.setStatus(DocumentStatus.CORBEILLE);
        doc.setMotifSuppression(motif);
        doc.setCommentaireSuppression(commentaireNet);
        doc.setEliminationBloquee(false);
        doc.setAlerteSuppressionLe(null);
        doc.setSuppressionPrevueLe(LocalDate.now().plusDays(delaiGraceJours(doc.getTypeDocument())));
        documentRepository.save(doc);
        meilisearchService.updateDocumentStatus(doc);

        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("motif", motif.name());
        if (commentaireNet != null) details.put("commentaire", commentaireNet);
        auditLogService.log(user, AuditAction.DOCUMENT_PLACE_CORBEILLE, AuditCible.DOCUMENT,
            doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Document \"" + doc.getTitre() + "\" (" + statutOrigine + ") envoyé à la corbeille — motif : "
                + motif + (commentaireNet != null ? " (" + commentaireNet + ")" : "")
                + ", suppression définitive prévue le " + doc.getSuppressionPrevueLe(),
            true, details);

        return doc.getSuppressionPrevueLe();
    }

    @Transactional
    public void restaurerDepuisCorbeille(UUID documentId, boolean renouvelerRetention, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);

        boolean autorise = estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
            .anyMatch(u -> u.getId().equals(user.getId()));
        if (!autorise)
        {
            throw new BusinessException(
                "Seul un éditeur ayant accès à ce document peut le restaurer depuis la corbeille");
        }

        if (doc.getStatus() != DocumentStatus.CORBEILLE)
        {
            throw new BusinessException("Ce document n'est pas dans la corbeille");
        }

        boolean retentionDepassee = doc.getRetentionUntil() != null
            && !doc.getRetentionUntil().isAfter(LocalDate.now());
        if (retentionDepassee && !renouvelerRetention)
        {
            throw new BusinessException("La date de rétention de ce document est dépassée depuis le "
                + doc.getRetentionUntil() + " — confirmez le renouvellement de la rétention pour le restaurer");
        }

        DocumentStatus statutRestaure = doc.getStatutAvantCorbeille() != null
            ? doc.getStatutAvantCorbeille() : DocumentStatus.ACTIVE;

        doc.setStatus(statutRestaure);
        doc.setStatutAvantCorbeille(null);
        doc.setSuppressionPrevueLe(null);
        doc.setMotifSuppression(null);
        doc.setCommentaireSuppression(null);
        doc.setEliminationBloquee(false);
        doc.setBlocageMotif(null);
        doc.setBlocagePar(null);
        doc.setBlocageLe(null);
        doc.setAlerteSuppressionLe(null);
        if (retentionDepassee)
        {
            Long anneesRetention = doc.getTypeDocument().getRetention() != null
                ? doc.getTypeDocument().getRetention().getRetentionYears() : null;
            doc.setRetentionUntil(anneesRetention != null ? LocalDate.now().plusYears(anneesRetention) : null);
        }
        documentRepository.save(doc);
        meilisearchService.updateDocumentStatus(doc);

        auditLogService.log(user, AuditAction.DOCUMENT_RESTAURE_CORBEILLE, AuditCible.DOCUMENT,
            doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Document \"" + doc.getTitre() + "\" restauré depuis la corbeille (statut " + statutRestaure + ")"
                + (retentionDepassee ? " — rétention renouvelée jusqu'au " + doc.getRetentionUntil() : ""),
            true);
    }

    /**
     * Suppression définitive immédiate, demandée par un éditeur, d'un document arrivé en FIN DE VIE
     * dont le sort final (CONSERVER/TRIER) exclut la purge automatique — voir
     * DocumentRetentionService.supprimerDefinitivementManuellement pour les règles. Motif obligatoire.
     * Même autorisation que envoyerCorbeille/restaurerDepuisCorbeille (éditeur ayant accès).
     */
    @Transactional
    public void supprimerDefinitivementDepuisCorbeille(UUID documentId, MotifSuppression motif, String commentaire,
                                                       UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);
        verifierEditeurAvecAcces(user, doc, "le supprimer définitivement");

        documentRetentionService.supprimerDefinitivementManuellement(doc, user, motif, commentaire);
    }

    /** Bloque la suppression automatique d'un document en corbeille (motif obligatoire). */
    @Transactional
    public void bloquerEliminationCorbeille(UUID documentId, String motif, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);
        verifierEditeurAvecAcces(user, doc, "bloquer sa suppression");

        documentRetentionService.bloquerElimination(doc, user, motif);
    }

    /** Débloque : nouveau délai de grâce complet. */
    @Transactional
    public void debloquerEliminationCorbeille(UUID documentId, String motif, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);
        verifierEditeurAvecAcces(user, doc, "débloquer sa suppression");

        documentRetentionService.debloquerElimination(doc, user, motif);
    }

    private void verifierEditeurAvecAcces(User user, Document doc, String action)
    {
        boolean autorise = estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
            .anyMatch(u -> u.getId().equals(user.getId()));
        if (!autorise)
        {
            throw new BusinessException("Seul un éditeur ayant accès à ce document peut " + action);
        }
    }

    // 8. LOCALISATION PHYSIQUE — modification après coup
    
    @Transactional
    public DocumentDetailDto modifierEmplacementPhysique(
        UUID documentId, UUID physicalLocationId, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);

        boolean autorise = estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
            .anyMatch(u -> u.getId().equals(user.getId()));
        if (!autorise)
        {
            throw new BusinessException(
                "Seul un éditeur ayant accès à ce document peut modifier son emplacement physique");
        }

        var ancien = doc.getPhysicalLocation();
        if (physicalLocationId == null)
        {
            doc.setPhysicalLocation(null);
        }
        else
        {
            doc.setPhysicalLocation(
                physicalLocationService.resolvePourRattachement(physicalLocationId, doc));
        }
        documentRepository.save(doc);

        auditLogService.log(user, AuditAction.DOCUMENT_EMPLACEMENT_MODIFIE, AuditCible.DOCUMENT,
            doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Emplacement physique du document \"" + doc.getTitre() + "\" changé de "
                + (ancien != null ? "\"" + ancien.getName() + "\"" : "aucun") + " vers "
                + (doc.getPhysicalLocation() != null ? "\"" + doc.getPhysicalLocation().getName() + "\"" : "aucun"),
            true);

        return getDetail(documentId, userDetails);
    }

    @Transactional
    public DocumentDetailDto modifierAcces(
        UUID documentId, ChangerAccesRequestDto dto, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);

        boolean autorise = estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
            .anyMatch(u -> u.getId().equals(user.getId()));
        if (!autorise)
        {
            throw new BusinessException(
                "Seul un éditeur ayant accès à ce document peut modifier son accès");
        }

        if (doc.getDossier() != null && doc.getDossier().getAccess() == TypeAccess.PRIVE)
        {
            throw new BusinessException(
                "Ce document hérite de la confidentialité de son dossier (\"" + doc.getDossier().getNom()
                + "\") — modifiez l'accès du dossier plutôt que celui de ce document");
        }

        TypeAccess ancienAcces = doc.getAccess();
        if (dto.getAccess() == null || dto.getAccess() == ancienAcces)
        {
            return getDetail(documentId, userDetails);
        }

        if (dto.getAccess() == TypeAccess.PRIVE)
        {
            GroupeAccess g = new GroupeAccess();
            g.setCreateAt(LocalDate.now());

            List<User> membres = new ArrayList<>();
            membres.add(user);
            if (dto.getGroupeMembresIds() != null && !dto.getGroupeMembresIds().isEmpty())
            {
                List<User> autres = userRepository.findAllById(
                    dto.getGroupeMembresIds().stream()
                        .filter(id -> !id.equals(user.getId()))
                        .toList());
                membres.addAll(autres);
            }
            g.setMembres(membres);
            doc.setGroupe(groupeAccessRepository.save(g));
        }
        else
        {
            doc.setGroupe(null);
        }

        doc.setAccess(dto.getAccess());
        documentRepository.save(doc);

        meilisearchService.updateDocumentAccess(doc);

        auditLogService.log(user, AuditAction.DOCUMENT_ACCES_MODIFIE, AuditCible.DOCUMENT,
            doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Accès du document \"" + doc.getTitre() + "\" changé de " + ancienAcces
                + " à " + dto.getAccess(),
            true);

        if (ancienAcces == TypeAccess.PUBLIC && dto.getAccess() == TypeAccess.PRIVE)
        {
            purgerAttestationSiExiste(doc, "changement d'accès PUBLIC → PRIVÉ");
        }

        return getDetail(documentId, userDetails);
    }

    private void purgerAttestationSiExiste(Document doc, String raisonAudit)
    {
        for (Attestation attestation : attestationRepository.findAllByDocumentId(doc.getId()))
        {
            auditLogService.log(null, AuditAction.ATTESTATION_PURGEE, AuditCible.DOCUMENT,
                doc.getId().toString(),
                doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
                "Attestation d'archivage purgée pour \"" + doc.getTitre() + "\" — " + raisonAudit,
                true);
            attestationRepository.delete(attestation);
        }}

    
    // 8a. RECLASSEMENT — document archivé par erreur dans le mauvais type

    /**
     * Corrige le classement d'un document archivé par erreur : nouveau type (avec les métadonnées de ce
     * type), et si demandé nouveau dossier / nouvel emplacement — en une seule opération, sans rien
     * supprimer ni réarchiver : le fichier, ses empreintes, sa signature et son horodatage restent
     * valables (ils portent sur le fichier, pas sur son classement). Ne concerne que la version ouverte.
     *
     * Changer de type change la règle de conservation : retentionUntil est recalculé depuis la date
     * d'archivage. Si l'échéance obtenue est déjà passée, le document suit le circuit normal de fin de
     * vie (tâche planifiée : corbeille avec son délai de grâce complet) — jamais de suppression immédiate.
     *
     * Les valeurs de l'ancien type sont conservées dans le journal du document (details de l'entrée
     * DOCUMENT_RECLASSE) : reprises par nom de champ quand le nouveau type a le même, sinon supprimées
     * de la fiche mais jamais perdues. Même autorisation que les autres corrections (éditeur ayant accès).
     */
    @Transactional
    public DocumentDetailDto reclasser(UUID documentId, made.archive.dto.ReclassementRequestDto req, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);
        verifierEditeurAvecAcces(user, doc, "le reclasser");

        if (doc.getStatus() == DocumentStatus.CORBEILLE)
        {
            throw new BusinessException("Ce document est dans la corbeille — restaurez-le avant de le reclasser");
        }

        TypeDocument ancienType = doc.getTypeDocument();
        TypeDocumentService.refuserSiSysteme(ancienType);
        boolean changeType = req.getTypeDocumentId() != null && !req.getTypeDocumentId().equals(ancienType.getId());
        if (!changeType && !req.isModifierDossier() && !req.isModifierEmplacement() && !req.isModifierActivite())
        {
            throw new BusinessException("Aucun changement demandé");
        }
        String activiteAvant = made.archive.service.organisation.PlanClassementService
            .chemin(made.archive.service.organisation.PlanClassementService.activiteEffective(doc));

        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        List<String> valeursPourIndex = List.of();

        if (changeType)
        {
            TypeDocument nouveauType = typeDocumentService.getTypeDocumentById(req.getTypeDocumentId());
            Long uoDoc = doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null;
            if (uoDoc == null || nouveauType.getUniteOrganisationnelle() == null
                || !uoDoc.equals(nouveauType.getUniteOrganisationnelle().getId()))
            {
                throw new BusinessException("Le nouveau type doit appartenir à la même unité organisationnelle que le document");
            }

            // Un emplacement qui n'accepte QUE l'ancien type ne peut plus recevoir ce document, sauf si
            // l'éditeur en choisit un autre dans la même opération.
            var emplacement = doc.getPhysicalLocation();
            if (emplacement != null && !req.isModifierEmplacement()
                && emplacement.getModeContrainte() == made.archive.entite.LocationModeContrainte.TYPE_UNIQUE
                && emplacement.getTypeDocumentAccepte() != null
                && !emplacement.getTypeDocumentAccepte().getId().equals(nouveauType.getId()))
            {
                throw new BusinessException("L'emplacement actuel (\"" + emplacement.getName() + "\") n'accepte que le type \""
                    + emplacement.getTypeDocumentAccepte().getNom() + "\" — choisissez un autre emplacement pour ce reclassement");
            }

            // Valeurs actuelles (lues par requête, pas via doc.getData() : cette collection resterait
            // périmée dans la session après le remplacement ci-dessous).
            java.util.Map<String, String> valeursAvant = new java.util.LinkedHashMap<>();
            for (DataType dt : dataTypeRepository.findByDocument_Id(documentId))
            {
                valeursAvant.put(dt.getMetaData() != null ? dt.getMetaData().getNom() : "(sans libellé)", dt.getValeur());
            }

            List<MetaData> defs = nouveauType.getMetaData() != null ? nouveauType.getMetaData() : List.of();
            Map<String, MetaData> defsParNom = defs.stream().collect(Collectors.toMap(
                m -> made.archive.util.NormalisationNoms.normaliser(m.getNom()), m -> m, (a, b) -> a));

            List<String> erreurs = new ArrayList<>();
            List<DataType> aEnregistrer = new ArrayList<>();
            java.util.Map<String, String> valeursApres = new java.util.LinkedHashMap<>();
            Set<String> champsRenseignes = new java.util.HashSet<>();
            for (DataTypeDto dto : req.getMetaData() != null ? req.getMetaData() : List.<DataTypeDto>of())
            {
                MetaData meta = dto.getNom() != null
                    ? defsParNom.get(made.archive.util.NormalisationNoms.normaliser(dto.getNom())) : null;
                if (meta == null)
                {
                    erreurs.add("Champ inconnu pour le type « " + nouveauType.getNom() + " » : " + dto.getNom());
                    continue;
                }
                String valeur = dto.getValeur() != null ? dto.getValeur().trim() : "";
                if (valeur.isEmpty()) continue;
                champsRenseignes.add(meta.getNom());
                DataType dataType = new DataType();
                dataType.setDocument(doc);
                dataType.setMetaData(meta);
                dataType.setValeur(valeur);
                aEnregistrer.add(dataType);
                valeursApres.put(meta.getNom(), valeur);
            }
            for (MetaData meta : defs)
            {
                if (Boolean.TRUE.equals(meta.getObligatoire()) && !champsRenseignes.contains(meta.getNom()))
                {
                    erreurs.add("Le champ '" + meta.getNom() + "' est obligatoire pour le type « " + nouveauType.getNom() + " »");
                }
            }
            if (!erreurs.isEmpty())
            {
                throw new BusinessException("Validation des métadonnées échouée : " + String.join(" | ", erreurs));
            }

            // Anciennes valeurs sans champ équivalent dans le nouveau type : retirées de la fiche, gardées dans le journal.
            java.util.Map<String, String> nonReprises = new java.util.LinkedHashMap<>();
            valeursAvant.forEach((nom, val) -> {
                if (!defsParNom.containsKey(made.archive.util.NormalisationNoms.normaliser(nom)))
                {
                    nonReprises.put(nom, val);
                }
            });

            LocalDate retentionAvant = doc.getRetentionUntil();
            Long annees = nouveauType.getRetention() != null ? nouveauType.getRetention().getRetentionYears() : null;
            LocalDate retentionApres = annees != null ? doc.getCreateAt().toLocalDate().plusYears(annees) : null;

            dataTypeRepository.deleteByDocumentId(documentId);
            dataTypeRepository.saveAll(aEnregistrer);
            doc.setTypeDocument(nouveauType);
            doc.setRetentionUntil(retentionApres);
            documentRepository.save(doc);

            valeursPourIndex = new ArrayList<>(valeursApres.values());
            purgerAttestationSiExiste(doc, "reclassement du document (type modifié)");

            details.put("typeAvant", ancienType.getNom());
            details.put("typeApres", nouveauType.getNom());
            details.put("conservationAvant", retentionAvant != null ? retentionAvant.toString() : "illimitée");
            details.put("conservationApres", retentionApres != null ? retentionApres.toString() : "illimitée");
            details.put("valeursAvant", valeursAvant);
            details.put("valeursApres", valeursApres);
            if (!nonReprises.isEmpty()) details.put("valeursNonReprises", nonReprises);
        }

        // Activité propre au document : explicitement choisie, ou remise à "suit son type" quand le type
        // change sans qu'une activité ait été précisée (l'ancienne exception n'a plus de raison d'être).
        if (req.isModifierActivite() || changeType)
        {
            made.archive.entite.PlanClassementNoeud demande = null;
            if (req.isModifierActivite() && req.getPlanClassementNoeudId() != null)
            {
                demande = planClassementNoeudRepository.findById(req.getPlanClassementNoeudId())
                    .orElseThrow(() -> new BusinessException("Activité introuvable : " + req.getPlanClassementNoeudId()));
                Long uoDocument = doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null;
                if (uoDocument == null || !demande.getUniteOrganisationnelle().getId().equals(uoDocument))
                {
                    throw new BusinessException("Cette activité appartient au plan de classement d'une autre UO");
                }
                var parDefaut = doc.getTypeDocument().getPlanClassementNoeud();
                if (parDefaut != null && parDefaut.getId().equals(demande.getId()))
                {
                    demande = null; // identique à celle du type : le document la suit simplement
                }
            }
            doc.setPlanClassementNoeud(demande);
            documentRepository.save(doc);
        }

        String activiteApres = made.archive.service.organisation.PlanClassementService
            .chemin(made.archive.service.organisation.PlanClassementService.activiteEffective(doc));
        boolean activiteChangee = !java.util.Objects.equals(activiteAvant, activiteApres);
        if (changeType || activiteChangee)
        {
            details.put("activiteAvant", activiteAvant != null ? activiteAvant : "non classé");
            details.put("activiteApres", activiteApres != null ? activiteApres : "non classé");
            Long uoLog = doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null;
            String description = "Document \"" + doc.getTitre() + "\" reclassé par " + user.getEmail() + " : "
                + (changeType
                    ? "type « " + ancienType.getNom() + " » → « " + doc.getTypeDocument().getNom() + " » ; échéance de conservation "
                        + details.get("conservationAvant") + " → " + details.get("conservationApres") + " ; "
                    : "")
                + "activité " + (activiteAvant != null ? activiteAvant : "non classé") + " → "
                + (activiteApres != null ? activiteApres : "non classé");
            auditLogService.log(user, AuditAction.DOCUMENT_RECLASSE, AuditCible.DOCUMENT,
                doc.getId().toString(), uoLog, description, true, details);
        }

        // Dossier / emplacement : mêmes règles et mêmes entrées de journal que les corrections séparées —
        // appelés dans la MÊME transaction, donc tout est annulé ensemble si l'un des changements est refusé.
        if (req.isModifierDossier())
        {
            modifierDossierDocument(documentId, req.getDossierId(), req.isFusionnerGroupes(), userDetails);
        }
        if (req.isModifierEmplacement())
        {
            modifierEmplacementPhysique(documentId, req.getPhysicalLocationId(), userDetails);
        }

        // En DERNIER : l'index n'est touché que si tous les changements ont été acceptés (best-effort).
        if (changeType)
        {
            meilisearchService.updateDocumentClassement(doc, valeursPourIndex);
        }

        return getDetail(documentId, userDetails);
    }

    // 8b. DOSSIER — rattacher, migrer ou détacher un document après coup

    @Transactional
    public DocumentDetailDto modifierDossierDocument(
        UUID documentId, Long nouveauDossierId, boolean fusionnerGroupes, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);

        boolean autorise = estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
            .anyMatch(u -> u.getId().equals(user.getId()));
        if (!autorise)
        {
            throw new BusinessException(
                "Seul un éditeur ayant accès à ce document peut le rattacher ou le détacher d'un dossier");
        }

        Dossier ancien = doc.getDossier();

        if (nouveauDossierId == null)
        {
            if (ancien != null && ancien.getGroupe() != null && doc.getGroupe() != null
                && doc.getGroupe().getId().equals(ancien.getGroupe().getId()))
            {
                GroupeAccess copie = new GroupeAccess();
                copie.setMembres(new ArrayList<>(ancien.getGroupe().getMembres()));
                copie.setCreateAt(LocalDate.now());
                copie = groupeAccessRepository.save(copie);
                doc.setGroupe(copie);
            }

            doc.setDossier(null);
        }
        else
        {
            Dossier nouveau = dossierRepository.findById(nouveauDossierId)
                .orElseThrow(() -> new BusinessException("Dossier introuvable : " + nouveauDossierId));

            if (doc.getUniteOrganisationnelle() == null || nouveau.getUniteOrganisationnelle() == null
                || !nouveau.getUniteOrganisationnelle().getId().equals(doc.getUniteOrganisationnelle().getId()))
            {
                throw new BusinessException(
                    "Ce dossier n'appartient pas à la même unité organisationnelle que le document");
            }

            if (nouveau.getAccess() == TypeAccess.PRIVE)
            {
                boolean estMembreDuDossier = nouveau.getGroupe() != null
                    && nouveau.getGroupe().getMembres().stream()
                        .anyMatch(m -> m.getId().equals(user.getId()));
                if (!estMembreDuDossier)
                {
                    throw new BusinessException(
                        "Ce dossier est privé — seul un membre de son groupe d'accès peut y rattacher un document");
                }
            }

            TypeDocument type = doc.getTypeDocument();
            List<TypeDocument> typesAttendus = nouveau.getTypesDocumentsAttendus();
            boolean dejaPresent = typesAttendus != null
                && typesAttendus.stream().anyMatch(t -> t.getId().equals(type.getId()));
            if (!dejaPresent)
            {
                if (typesAttendus == null)
                {
                    typesAttendus = new ArrayList<>();
                }
                else
                {
                    typesAttendus = new ArrayList<>(typesAttendus);
                }
                typesAttendus.add(type);
                nouveau.setTypesDocumentsAttendus(typesAttendus);
                dossierRepository.save(nouveau);

                auditLogService.log(user, AuditAction.DOSSIER_TYPES_AJOUTES, AuditCible.DOSSIER,
                    nouveau.getId().toString(),
                    nouveau.getUniteOrganisationnelle() != null ? nouveau.getUniteOrganisationnelle().getId() : null,
                    "Type \"" + type.getNom() + "\" ajouté automatiquement au dossier " + nouveau.getNom()
                        + " (document \"" + doc.getTitre() + "\" rattaché)",
                    true);
            }

            if (doc.getAccess() == TypeAccess.PRIVE && doc.getGroupe() != null
                && nouveau.getAccess() == TypeAccess.PRIVE && nouveau.getGroupe() != null
                && !doc.getGroupe().getId().equals(nouveau.getGroupe().getId()))
            {
                List<User> manquants = membresManquants(doc.getGroupe(), nouveau.getGroupe());
                if (!manquants.isEmpty())
                {
                    if (!fusionnerGroupes)
                    {
                        throw new BusinessException(
                            "Le groupe du document et celui du dossier n'ont pas les mêmes membres — "
                            + "confirmation requise avant de les fusionner (voir verifierFusionGroupe)");
                    }

                    GroupeAccess groupeDossier = nouveau.getGroupe();
                    List<User> membresFusionnes = new ArrayList<>(groupeDossier.getMembres());
                    membresFusionnes.addAll(manquants);
                    groupeDossier.setMembres(membresFusionnes);
                    groupeAccessRepository.save(groupeDossier);

                    auditLogService.log(user, AuditAction.GROUPE_MEMBRE_AJOUTE, AuditCible.DOCUMENT,
                        doc.getId().toString(),
                        doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
                        manquants.size() + " membre(s) du groupe du document \"" + doc.getTitre()
                            + "\" fusionné(s) dans le groupe du dossier " + nouveau.getNom()
                            + " (rattachement confirmé par l'éditeur)",
                        true);
                }

                doc.setGroupe(nouveau.getGroupe());
            }

            doc.setDossier(nouveau);
        }

        documentRepository.save(doc);

        auditLogService.log(user, AuditAction.DOCUMENT_DOSSIER_MODIFIE, AuditCible.DOCUMENT,
            doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Dossier du document \"" + doc.getTitre() + "\" changé de "
                + (ancien != null ? "\"" + ancien.getNom() + "\"" : "aucun") + " vers "
                + (doc.getDossier() != null ? "\"" + doc.getDossier().getNom() + "\"" : "aucun"),
            true);

        return getDetail(documentId, userDetails);
    }

    @Transactional(readOnly = true)
    public FusionGroupeCheckDto verifierFusionGroupe(UUID documentId, Long dossierId, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);
        Dossier dossier = dossierRepository.findById(dossierId)
            .orElseThrow(() -> new BusinessException("Dossier introuvable : " + dossierId));

        if (doc.getAccess() != TypeAccess.PRIVE || doc.getGroupe() == null
            || dossier.getAccess() != TypeAccess.PRIVE || dossier.getGroupe() == null
            || doc.getGroupe().getId().equals(dossier.getGroupe().getId()))
        {
            return FusionGroupeCheckDto.builder().groupesDifferents(false).build();
        }

        List<User> manquants = membresManquants(doc.getGroupe(), dossier.getGroupe());
        return FusionGroupeCheckDto.builder()
            .groupesDifferents(!manquants.isEmpty())
            .membresQuiSerontAjoutes(manquants.stream()
                .map(u -> u.getPrenom() + " " + u.getNom())
                .toList())
            .build();
    }

    /** Membres de "source" absents de "cible" — comparés par id, jamais par référence d'objet. */
    private List<User> membresManquants(GroupeAccess source, GroupeAccess cible)
    {
        List<User> membresSource = source.getMembres() != null ? source.getMembres() : List.of();
        List<User> membresCible  = cible.getMembres()  != null ? cible.getMembres()  : List.of();
        return membresSource.stream()
            .filter(m -> membresCible.stream().noneMatch(c -> c.getId().equals(m.getId())))
            .toList();
    }

    // 9. MÉTADONNÉES — modification après coup (valeurs uniquement, jamais le fichier/titre/type)
    
    @Transactional
    public DocumentDetailDto modifierMetaData(
        UUID documentId, List<DataTypeDto> nouvellesValeurs, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);

        boolean autorise = estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
            .anyMatch(u -> u.getId().equals(user.getId()));
        if (!autorise)
        {
            throw new BusinessException(
                "Seul un éditeur ayant accès à ce document peut modifier ses métadonnées");
        }

        List<MetaData> metaDataDefinies = doc.getTypeDocument().getMetaData();
        Map<String, MetaData> parNom = metaDataDefinies.stream()
            .collect(Collectors.toMap(MetaData::getNom, m -> m, (a, b) -> a));

        List<String> erreurs = new ArrayList<>();
        List<DataType> aEnregistrer = new ArrayList<>();

        for (DataTypeDto dto : nouvellesValeurs)
        {
            MetaData meta = parNom.get(dto.getNom());
            if (meta == null)
            {
                erreurs.add("Champ inconnu pour ce type : " + dto.getNom());
                continue;
            }

            String valeur = dto.getValeur();
            if (Boolean.TRUE.equals(meta.getObligatoire()) && (valeur == null || valeur.isBlank()))
            {
                erreurs.add("Le champ '" + meta.getNom() + "' est obligatoire");
                continue;
            }
            if (valeur == null || valeur.isBlank())
            {
                continue;
            }

            DataType dataType = new DataType();
            dataType.setDocument(doc);
            dataType.setMetaData(meta);
            dataType.setValeur(valeur);
            aEnregistrer.add(dataType);
        }

        Set<String> nomsRecus = nouvellesValeurs.stream()
            .map(DataTypeDto::getNom).collect(Collectors.toSet());
        for (MetaData meta : metaDataDefinies)
        {
            if (Boolean.TRUE.equals(meta.getObligatoire()) && !nomsRecus.contains(meta.getNom()))
            {
                erreurs.add("Le champ '" + meta.getNom() + "' est obligatoire");
            }
        }

        if (!erreurs.isEmpty())
        {
            throw new BusinessException("Validation des métadonnées échouée : "
                + String.join(" | ", erreurs));
        }

        dataTypeRepository.deleteByDocumentId(documentId);
        dataTypeRepository.saveAll(aEnregistrer);

        auditLogService.log(user, AuditAction.DOCUMENT_METADATA_MODIFIEE, AuditCible.DOCUMENT,
            doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Métadonnées corrigées pour le document \"" + doc.getTitre() + "\"", true);

        long documentsVivantsDuType = documentRepository.countByTypeDocument_IdAndStatusNot(
            doc.getTypeDocument().getId(), DocumentStatus.DELETED);
        if (documentsVivantsDuType == 1 && doc.getTypeDocument().hasRegexGenerated())
        {
            typeDocumentService.viderRegexAutomatiquement(doc.getTypeDocument(), user,
                "métadonnées corrigées sur son seul document existant (\"" + doc.getTitre() + "\")");
        }

        return getDetail(documentId, userDetails);
    }

    // Helpers privés

    private DocumentPageDto getTousMesDocuments(User user, int page, int size)
    {
        Pageable pageable = PageRequest.of(
            Math.max(0, page - 1),
            Math.min(size, 50),
            Sort.by(Sort.Direction.DESC, "createAt")
        );

        Page<Document> pageResult = documentRepository
            .findByUploadedByIdAndStatusNotIn(
                user.getId(), STATUTS_EXCLUS_LECTURE, pageable);

        List<DocumentListItemDto> items = pageResult.getContent().stream()
            .map(doc -> toListItemDto(doc, user))
            .collect(Collectors.toList());

        return DocumentPageDto.builder()
            .content(items)
            .page(page)
            .size(size)
            .totalElements(pageResult.getTotalElements())
            .totalPages(pageResult.getTotalPages())
            .build();
    }

    /**
     * Interroge Meilisearch et retourne la liste ordonnée des UUIDs pertinents.
     * Filtre optionnel typeDocumentId appliqué dans la requête Meilisearch.
     */
    @SuppressWarnings("unchecked")
    private List<UUID> searchMeilisearch(
        String query, Long typeDocumentId, int page, int size)
    {
        try
        {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("q", query);
            body.put("page", page);
            body.put("hitsPerPage", Math.min(size, 50));
            body.put("attributesToRetrieve", List.of("id"));

            List<String> filters = new ArrayList<>();
            filters.add("status != DELETED AND status != CORBEILLE");
            if (typeDocumentId != null)
            {
                filters.add("typeDocumentId = " + typeDocumentId);
            }
            body.put("filter", String.join(" AND ", filters));

            WebClient client = webClientBuilder
                .baseUrl(meilisearchProperties.getHost())
                .defaultHeader("Authorization",
                    "Bearer " + meilisearchProperties.getSearchKey())
                .build();

            String responseJson = client.post()
                .uri("/indexes/" + INDEX_NAME + "/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            Map<String, Object> response = objectMapper.readValue(
                responseJson, new TypeReference<Map<String, Object>>() {});

            List<Map<String, Object>> hits = response.get("hits") instanceof List<?>
                ? (List<Map<String, Object>>) response.get("hits")
                : Collections.emptyList();

            return hits.stream()
                .map(hit -> {
                    Object idObj = hit.get("id");
                    if (idObj == null) return null;
                    try { return UUID.fromString(idObj.toString()); }
                    catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        }
        catch (Exception e)
        {
            log.warn("[DocumentService] Meilisearch indisponible, recherche vide : {}",
                     e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Télécharge un fichier depuis le stockage et retourne ses bytes.
     */
    private byte[] downloadFromStorage(String storageKey, UUID documentId, String context)
    {
        try (InputStream stream = storageService.download(storageKey))
        {
            if (stream == null)
            {
                throw new BusinessException("Fichier introuvable en stockage");
            }
            // Le PDF/A est chiffré au repos (AES-256-GCM) — déchiffrement
            // immédiat après lecture, avant tout envoi au client.
            byte[] bytes = documentEncryptionService.decrypt(stream.readAllBytes());
            log.info("[DocumentService] {} : {} bytes pour doc {}",
                     context, bytes.length, documentId);
            return bytes;
        }
        catch (BusinessException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[DocumentService] Erreur {} pour doc {} : {}",
                      context, documentId, e.getMessage());
            throw new BusinessException(
                "Impossible de récupérer le fichier : " + e.getMessage(), e);
        }
    }

    /**
     * Résout l'utilisateur connecté depuis UserDetails.
     */
    private User resolveUser(UserDetails userDetails)
    {
        return userRepository.findByEmail(userDetails.getUsername())
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));
    }

    private Document resolveDocument(UUID documentId, User user)
    {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new BusinessException(
                "Document introuvable : " + documentId));

        boolean estUploadeur = doc.getUploadedBy().getId().equals(user.getId());

        if (!estUploadeur)
        {
            boolean estMisDeCote = doc.getStatus() == DocumentStatus.CORRUPTED
                || doc.getStatus() == DocumentStatus.CORBEILLE;

            boolean autorise = estMisDeCote
                ? (
                    doc.getUniteOrganisationnelle() != null
                    && uniteOrganisationnelleService.aAutoriteSur(
                        doc.getUniteOrganisationnelle().getId(), user)
                  )
                  || (estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
                        .anyMatch(u -> u.getId().equals(user.getId())))
                : estVisibleNormalement(doc, user);

            if (!autorise)
            {
                throw new BusinessException(
                    "Accès refusé : ce document ne vous appartient pas");
            }
        }

        if (doc.getStatus() == DocumentStatus.DELETED)
        {
            throw new BusinessException("Ce document a été supprimé");
        }

        return doc;
    }

    private boolean estVisibleNormalement(Document doc, User user)
    {
        if (doc.getUniteOrganisationnelle() == null)
        {
            return false;
        }

        java.util.Set<Long> uoVisibles = uniteOrganisationnelleService.getUoIdsVisiblesPourLecture(user);
        if (uoVisibles != null && !uoVisibles.contains(doc.getUniteOrganisationnelle().getId()))
        {
            return false;
        }

        if (doc.getAccess() == TypeAccess.PUBLIC)
        {
            return true;
        }

        return doc.getGroupe() != null && doc.getGroupe().getMembres().stream()
            .anyMatch(m -> m.getId().equals(user.getId()));
    }

    public List<User> getUtilisateursAyantAcces(Document document)
    {
        if (document.getAccess() == TypeAccess.PRIVE)
        {
            return document.getGroupe() != null
                ? document.getGroupe().getMembres()
                : List.of(document.getUploadedBy());
        }
        return document.getUniteOrganisationnelle() != null
            ? userRepository.findByUniteOrganisationnelleId(document.getUniteOrganisationnelle().getId())
            : List.of(document.getUploadedBy());
    }

    private boolean estEditeur(User user)
    {
        return user.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.EDITOR);
    }

    /**
     * Convertit un Document en DTO léger pour la liste.
     */
    private DocumentListItemDto toListItemDto(Document doc, User currentUser)
    {
        boolean peutGererCorbeille = estEditeur(currentUser) && getUtilisateursAyantAcces(doc).stream()
            .anyMatch(u -> u.getId().equals(currentUser.getId()));

        return DocumentListItemDto.builder()
            .documentId(doc.getId())
            .titre(doc.getTitre())
            .typeDocumentId(doc.getTypeDocument().getId())
            .typeDocumentNom(doc.getTypeDocument().getNom())
            .status(doc.getStatus().name())
            .access(doc.getAccess().name())
            .retentionUntil(doc.getRetentionUntil())
            .createAt(doc.getCreateAt())
            .versionLabel(DocumentVersionLabels.compute(doc))
            .retentionYearsType(doc.getTypeDocument().getRetention() != null
                ? doc.getTypeDocument().getRetention().getRetentionYears() : null)
            .peutGererCorbeille(peutGererCorbeille)
            .build();
    }

    /**
     * Historique complet de la chaîne de versions (v1 → ... → Final),
     * y compris le document lui-même. Liste vide si aucun historique
     * (racine == null et pas de successeur) — le badge/l'historique restent
     * cohérents : un document jamais versionné n'a pas d'entrée ici non plus.
     */
    private List<DocumentVersionDto> getHistoriqueVersions(Document doc)
    {
        UUID racineId = doc.getDocumentRacine() != null
            ? doc.getDocumentRacine().getId()
            : doc.getId();

        List<Document> chaine = documentRepository.findChaineVersions(racineId);
        if (chaine.size() <= 1)
        {
            return List.of();
        }

        return chaine.stream()
            .map(d -> DocumentVersionDto.builder()
                .documentId(d.getId())
                .titre(d.getTitre())
                .version(d.getVersion() != null ? d.getVersion() : 0)
                .versionLabel(DocumentVersionLabels.compute(d))
                .estVersionActuelle(d.isDerniereVersion())
                .createAt(d.getCreateAt())
                .uploadedByNom(d.getUploadedBy() != null
                    ? d.getUploadedBy().getPrenom() + " " + d.getUploadedBy().getNom() : null)
                .build())
            .toList();
    }

    /**
     * Nettoie un nom de fichier pour le Content-Disposition.
     */
    private String sanitizeFilename(String name)
    {
        if (name == null || name.isBlank()) return "document";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}