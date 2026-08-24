package made.archive.service.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.MeilisearchProperties;
import made.archive.dto.DocumentDetailDto;
import made.archive.dto.DocumentFolderDto;
import made.archive.dto.DocumentListItemDto;
import made.archive.dto.DocumentPageDto;
import made.archive.dto.DocumentVersionDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.FixityCheckResult;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeAccess;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.FixityCheckResultRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.InputStream;
import java.time.LocalDate;
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

    private static final String INDEX_NAME        = "documents";

    // ═══════════════════════════════════════════════════════════════════
    // 1. DOSSIERS — grille de types avec compteurs
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Retourne les types de documents utilisés par l'éditeur connecté,
     * avec le nombre de documents par type.
     * Limité à FOLDER_PAGE_SIZE (10) par défaut.
     * Filtrage supplémentaire par nom de type possible (recherche locale).
     *
     * Source : BD uniquement.
     */
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

    // ═══════════════════════════════════════════════════════════════════
    // 2. LISTE — documents d'un type, paginés depuis la BD
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Retourne les documents de l'éditeur connecté pour un type donné.
     * Pagination BD, tri par date de création décroissante.
     * Exclut les documents DELETED.
     *
     * Source : BD uniquement.
     */
    @Transactional(readOnly = true)
    public DocumentPageDto getMesDocumentsByType(
        Long typeDocumentId,
        int page,
        int size,
        UserDetails userDetails)
    {
        User user = resolveUser(userDetails);

        Pageable pageable = PageRequest.of(
            Math.max(0, page - 1),
            Math.min(size, 50),
            Sort.by(Sort.Direction.DESC, "createAt")
        );

        Page<Document> pageResult = documentRepository
            .findByUploadedByIdAndTypeDocumentIdAndStatusNot(
                user.getId(),
                typeDocumentId,
                DocumentStatus.DELETED,
                pageable
            );

        List<DocumentListItemDto> items = pageResult.getContent().stream()
            .map(this::toListItemDto)
            .collect(Collectors.toList());

        return DocumentPageDto.builder()
            .content(items)
            .page(page)
            .size(size)
            .totalElements(pageResult.getTotalElements())
            .totalPages(pageResult.getTotalPages())
            .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 3. RECHERCHE HYBRIDE — Meilisearch (IDs) → BD (données)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Recherche full-text via Meilisearch, puis charge les documents
     * depuis la BD par leurs IDs.
     *
     * Filtre optionnel typeDocumentId : restreint la recherche à un type.
     * Le filtre uploadedBy est toujours appliqué côté BD (sécurité).
     *
     * Si query est vide : délègue à getMesDocumentsByType() ou liste tous.
     */
    @Transactional(readOnly = true)
    public DocumentPageDto rechercher(
        String query,
        Long typeDocumentId,
        int page,
        int size,
        UserDetails userDetails)
    {
        User user = resolveUser(userDetails);

        // Requête vide → liste BD directe
        if (query == null || query.isBlank())
        {
            if (typeDocumentId != null)
            {
                return getMesDocumentsByType(typeDocumentId, page, size, userDetails);
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
            .findByIdInAndUploadedByIdAndStatusNot(
                ids, user.getId(), DocumentStatus.DELETED);

        // Conserver l'ordre retourné par Meilisearch (pertinence)
        Map<UUID, Document> docMap = documents.stream()
            .collect(Collectors.toMap(Document::getId, d -> d));

        List<DocumentListItemDto> items = ids.stream()
            .map(docMap::get)
            .filter(Objects::nonNull)
            .map(this::toListItemDto)
            .collect(Collectors.toList());

        return DocumentPageDto.builder()
            .content(items)
            .page(page)
            .size(size)
            .totalElements(items.size())
            .totalPages(1)
            .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 4. DÉTAIL — métadonnées complètes d'un document
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Retourne le détail complet d'un document avec ses métadonnées.
     * Vérifie que l'utilisateur connecté est bien l'uploadeur.
     */
    @Transactional(readOnly = true)
    public DocumentDetailDto getDetail(UUID documentId, UserDetails userDetails)
    {
        User user     = resolveUser(userDetails);
        Document doc  = resolveDocument(documentId, user);

        String corruptionRaison = doc.getStatus() == DocumentStatus.CORRUPTED
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
            .suppressionPrevueLe(doc.getSuppressionPrevueLe())
            .peutEtreSupprime(estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
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
            .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 5. VISUALISATION — streamer le PDF/A inline (pour le lecteur PDF)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Retourne les bytes du PDF/A pour affichage inline dans le navigateur.
     * Content-Type : application/pdf
     * Le contrôleur pose Content-Disposition: inline.
     *
     * Vérifie que l'utilisateur est bien l'uploadeur avant de streamer.
     */
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

    // ═══════════════════════════════════════════════════════════════════
    // 6. TÉLÉCHARGEMENT PDF/A — Content-Disposition: attachment
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Retourne les bytes du PDF/A pour téléchargement.
     * Le contrôleur pose Content-Disposition: attachment; filename=...
     */
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

    /**
     * Résout un document pour la génération d'une attestation d'archivage —
     * exactement les mêmes règles d'accès que consulter/télécharger (voir
     * resolveDocument), pas ouvert à tout le monde : réservé à qui a
     * normalement accès au document. Utilisé par AttestationService.
     */
    @Transactional(readOnly = true)
    public Document resolveDocumentPourAttestation(UUID documentId, UserDetails userDetails)
    {
        User user = resolveUser(userDetails);
        return resolveDocument(documentId, user);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 7. SUPPRESSION D'UN DOCUMENT CORROMPU — planifiée, 3 jours de grâce
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Programme la suppression définitive d'un document CORROMPU dans 3 jours —
     * réservé à un ÉDITEUR de la liste d'accès normale du document (voir
     * getUtilisateursAyantAcces), pas seulement l'uploadeur : un document dont
     * l'uploadeur a quitté l'UO ne doit pas rester bloqué indéfiniment si
     * d'autres éditeurs y ont légitimement accès. Toujours pas un admin, même
     * avec autorité sur l'UO : "seulement côté éditeur". Pendant les 3 jours,
     * le document reste consultable/téléchargeable exactement comme avant
     * (resolveDocument ne bloque que sur DELETED) ; c'est
     * DocumentRetentionService qui purge réellement une fois l'échéance
     * atteinte (même mécanisme tombstone que la fin de rétention).
     */
    @Transactional
    public void planifierSuppression(UUID documentId, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);

        boolean autorise = estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
            .anyMatch(u -> u.getId().equals(user.getId()));
        if (!autorise)
        {
            throw new BusinessException(
                "Seul un éditeur ayant accès à ce document peut demander sa suppression");
        }

        if (doc.getStatus() != DocumentStatus.CORRUPTED)
        {
            throw new BusinessException(
                "Seul un document détecté corrompu peut être planifié pour suppression");
        }

        if (doc.getSuppressionPrevueLe() != null)
        {
            throw new BusinessException("La suppression de ce document est déjà planifiée pour le "
                + doc.getSuppressionPrevueLe());
        }

        doc.setSuppressionPrevueLe(LocalDate.now().plusDays(3));
        documentRepository.save(doc);

        auditLogService.log(user, AuditAction.DOCUMENT_SUPPRESSION_PLANIFIEE, AuditCible.DOCUMENT,
            doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Suppression définitive planifiée pour le " + doc.getSuppressionPrevueLe()
                + " — document corrompu \"" + doc.getTitre() + "\"",
            true);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 8. LOCALISATION PHYSIQUE — modification après coup
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Modifie (ou retire, si physicalLocationId == null) l'emplacement
     * physique d'un document — même règle d'autorisation que
     * planifierSuppression : réservé à un ÉDITEUR de la liste d'accès
     * normale du document (voir getUtilisateursAyantAcces), pas ouvert à un
     * simple lecteur. L'emplacement choisi est validé par
     * PhysicalLocationService.resolvePourRattachement (point de stockage
     * ACTIF, même UO que le document).
     */
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

    // ═══════════════════════════════════════════════════════════════════
    // Helpers privés
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Liste tous les documents de l'éditeur, tous types confondus.
     */
    private DocumentPageDto getTousMesDocuments(User user, int page, int size)
    {
        Pageable pageable = PageRequest.of(
            Math.max(0, page - 1),
            Math.min(size, 50),
            Sort.by(Sort.Direction.DESC, "createAt")
        );

        Page<Document> pageResult = documentRepository
            .findByUploadedByIdAndStatusNot(
                user.getId(), DocumentStatus.DELETED, pageable);

        List<DocumentListItemDto> items = pageResult.getContent().stream()
            .map(this::toListItemDto)
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
            filters.add("status != DELETED");
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

    /**
     * Résout un document en vérifiant que l'utilisateur y a droit :
     *   - l'uploadeur a toujours accès, quel que soit le statut ;
     *   - sinon, pour un document SAIN, quiconque a normalement accès (public
     *     de son UO, ou membre du groupe privé) — quel que soit son rôle, voir
     *     estVisibleNormalement ; c'est ce qui permet à un simple USER de
     *     consulter/télécharger un document pour lequel il est autorisé, pas
     *     seulement à son propre uploadeur (ROUTE FINALE de "USER consulte et
     *     télécharge les documents auxquels il est autorisé") ;
     *   - sinon, pour un document CORROMPU, restreint à l'ADMIN/ADMIN_UO ayant
     *     autorité sur son UO, ou à un ÉDITEUR de sa liste d'accès (voir
     *     getUtilisateursAyantAcces) — investigation, suppression ou
     *     remplacement (voir FixityCheckService) ; un simple USER ne le voit
     *     plus tant qu'il reste corrompu, même s'il y avait normalement accès.
     */
    private Document resolveDocument(UUID documentId, User user)
    {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new BusinessException(
                "Document introuvable : " + documentId));

        boolean estUploadeur = doc.getUploadedBy().getId().equals(user.getId());

        if (!estUploadeur)
        {
            boolean autorise = doc.getStatus() == DocumentStatus.CORRUPTED
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

    /**
     * Visibilité normale d'un document SAIN — même règle que
     * DocumentAccessService (listes/recherche) : périmètre UO (null = ADMIN,
     * pas de restriction ; ADMIN_UO = son UO + descendantes ; EDITOR/USER =
     * leur propre UO) ET (PUBLIC, ou membre du GroupeAccess si PRIVÉ).
     */
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

    /**
     * Utilisateurs ayant accès à ce document — PUBLIC : tous les membres de
     * son UO ; PRIVÉ : les membres de son GroupeAccess, que ce groupe soit
     * propre au document ou hérité de son projet privé (voir
     * DocumentUploadeService — aucune différence de traitement nécessaire ici,
     * document.access/document.groupe reflètent déjà correctement l'héritage).
     * Utilisé pour la suppression d'un document corrompu, sa notification de
     * corruption, et sa visibilité restreinte une fois corrompu.
     */
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
    private DocumentListItemDto toListItemDto(Document doc)
    {
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