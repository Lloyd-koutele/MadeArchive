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
import made.archive.dto.DataTypeDto;
import made.archive.dto.FusionGroupeCheckDto;
import made.archive.entite.GroupeAccess;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.DataType;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.FixityCheckResult;
import made.archive.entite.MetaData;
import made.archive.entite.Projet;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeAccess;
import made.archive.entite.TypeDocument;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.FixityCheckResultRepository;
import made.archive.repository.ProjetRepository;
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
    private final made.archive.repository.DataTypeRepository dataTypeRepository;
    private final TypeDocumentService typeDocumentService;
    private final ProjetRepository projetRepository;
    private final made.archive.repository.GroupeAccessRepository groupeAccessRepository;

    private static final String INDEX_NAME        = "documents";

    /**
     * Statuts à exclure de tout listage/recherche NORMAL — tombstoné
     * (DELETED) et mis de côté volontairement (CORBEILLE, voir
     * envoyerCorbeille). Seule la corbeille elle-même (DocumentAccessService
     * .getDocumentsCorbeille) montre les documents CORBEILLE.
     */
    private static final List<DocumentStatus> STATUTS_EXCLUS_LECTURE =
        List.of(DocumentStatus.DELETED, DocumentStatus.CORBEILLE);

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
            .findByUploadedByIdAndTypeDocumentIdAndStatusNotIn(
                user.getId(),
                typeDocumentId,
                STATUTS_EXCLUS_LECTURE,
                pageable
            );

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
            .findByIdInAndUploadedByIdAndStatusNotIn(
                ids, user.getId(), STATUTS_EXCLUS_LECTURE);

        // Conserver l'ordre retourné par Meilisearch (pertinence)
        Map<UUID, Document> docMap = documents.stream()
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

        // Un document corrompu envoyé à la corbeille garde CORRUPTED dans
        // statutAvantCorbeille (voir Document.statutAvantCorbeille) — sa
        // raison de corruption doit rester affichée (badge), pas disparaître
        // simplement parce que son status affiché est devenu CORBEILLE.
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
            .projetId(doc.getProjet() != null ? doc.getProjet().getId() : null)
            .projetNom(doc.getProjet() != null ? doc.getProjet().getNom() : null)
            .peutModifierProjet(estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
                .anyMatch(u -> u.getId().equals(user.getId())))
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
    // 7. CORBEILLE — suppression volontaire (3 jours de grâce, restaurable)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Envoie un document à la corbeille — N'IMPORTE QUEL document non déjà
     * DELETED/CORBEILLE (plus seulement un corrompu, voir historique :
     * l'ancien planifierSuppression était restreint à CORRUPTED). Réservé à
     * un ÉDITEUR de la liste d'accès normale du document (voir
     * getUtilisateursAyantAcces), pas seulement l'uploadeur — un document
     * dont l'uploadeur a quitté l'UO ne doit pas rester bloqué indéfiniment
     * si d'autres éditeurs y ont légitimement accès. Jamais un admin seul,
     * même avec autorité sur l'UO : "seulement côté éditeur", comme pour
     * modifierMetaData/modifierEmplacementPhysique/modifierProjetDocument.
     *
     * Le statut d'origine est conservé dans statutAvantCorbeille (un document
     * CORROMPU envoyé à la corbeille redevient CORROMPU à la restauration,
     * badge inclus — voir Document.statutAvantCorbeille). Pendant les 3
     * jours, le document reste consultable/téléchargeable par les mêmes
     * profils que le circuit CORROMPU (resolveDocument), mais disparaît de
     * tout listage/recherche normal (voir STATUTS_EXCLUS_LECTURE) — seule la
     * corbeille elle-même (DocumentAccessService.getDocumentsCorbeille) le
     * montre. DocumentRetentionService purge réellement une fois l'échéance
     * atteinte (même mécanisme tombstone que la fin de rétention).
     */
    @Transactional
    public void envoyerCorbeille(UUID documentId, UserDetails userDetails)
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
        doc.setStatutAvantCorbeille(statutOrigine);
        doc.setStatus(DocumentStatus.CORBEILLE);
        doc.setSuppressionPrevueLe(LocalDate.now().plusDays(3));
        documentRepository.save(doc);

        auditLogService.log(user, AuditAction.DOCUMENT_PLACE_CORBEILLE, AuditCible.DOCUMENT,
            doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Document \"" + doc.getTitre() + "\" (" + statutOrigine + ") envoyé à la corbeille — "
                + "suppression définitive prévue le " + doc.getSuppressionPrevueLe(),
            true);
    }

    /**
     * Restaure un document depuis la corbeille — même règle d'autorisation
     * qu'envoyerCorbeille. Rend au document exactement son statut d'avant
     * (voir Document.statutAvantCorbeille) : un document CORROMPU restauré
     * redevient CORROMPU, pas ACTIVE — restaurer ne "répare" pas le fichier,
     * seulement l'action de suppression est annulée.
     */
    @Transactional
    public void restaurerDepuisCorbeille(UUID documentId, UserDetails userDetails)
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

        DocumentStatus statutRestaure = doc.getStatutAvantCorbeille() != null
            ? doc.getStatutAvantCorbeille() : DocumentStatus.ACTIVE;

        doc.setStatus(statutRestaure);
        doc.setStatutAvantCorbeille(null);
        doc.setSuppressionPrevueLe(null);
        documentRepository.save(doc);

        auditLogService.log(user, AuditAction.DOCUMENT_RESTAURE_CORBEILLE, AuditCible.DOCUMENT,
            doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Document \"" + doc.getTitre() + "\" restauré depuis la corbeille (statut " + statutRestaure + ")",
            true);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 8. LOCALISATION PHYSIQUE — modification après coup
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Modifie (ou retire, si physicalLocationId == null) l'emplacement
     * physique d'un document — même règle d'autorisation que
     * envoyerCorbeille : réservé à un ÉDITEUR de la liste d'accès
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
    // 8b. PROJET — rattacher, migrer ou détacher un document après coup
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Change le projet d'un document — même règle d'autorisation que
     * modifierEmplacementPhysique/modifierMetaData (éditeur de la liste
     * d'accès normale du document). nouveauProjetId == null détache le
     * document de son projet actuel ("le faire sortir du projet") ;
     * une valeur migre le document vers ce projet (que celui-ci en ait
     * déjà un ou non).
     *
     * Au détachement, si le document partageait encore le GroupeAccess de
     * ce projet (fusion antérieure ou héritage direct à l'upload — voir
     * DocumentUploadeService), il reçoit sa propre copie indépendante,
     * figée aux membres actuels : sans ça, il resterait exposé pour
     * toujours à quiconque rejoint le groupe du projet PLUS TARD, alors
     * qu'il n'en fait plus partie.
     *
     * Deux gardes supplémentaires, propres au projet CIBLE :
     *   - même UO que le document (jamais un document migré hors de son UO) ;
     *   - si le projet cible est PRIVÉ, l'acteur doit être membre de son
     *     groupe d'accès — sans quoi la confidentialité du projet serait
     *     contournable en y rattachant un document depuis l'extérieur.
     *
     * Aucune validation stricte contre les types attendus du projet cible —
     * un document d'un type hors-liste peut toujours être rattaché. En
     * revanche, si son type n'y figure pas encore, il est ajouté
     * automatiquement à Projet.typesDocumentsAttendus (jamais retiré
     * automatiquement au détachement — voir retirerTypeAttendu pour le
     * retrait volontaire) : un document rattaché doit toujours être
     * trouvable en parcourant son projet, jamais orphelin de la navigation
     * par types (voir ProjetsPanel côté client, qui ne liste les documents
     * que via ces dossiers de type).
     *
     * Document PRIVÉ rattaché à un projet PRIVÉ dont le groupe diffère : le
     * document.groupe ne change JAMAIS tout seul silencieusement — voir
     * verifierFusionGroupe, appelée par le client AVANT cette méthode pour
     * savoir s'il faut avertir l'éditeur. fusionnerGroupes doit valoir true
     * pour que la fusion ait lieu ; sinon, un écart entre les deux groupes
     * fait échouer l'appel plutôt que de fusionner sans confirmation. La
     * fusion elle-même : les membres du groupe du document manquants dans
     * celui du projet y sont ajoutés (union), puis document.groupe pointe
     * ensuite vers CE MÊME GroupeAccess que le projet — un lien permanent,
     * pas un instantané, exactement comme DocumentUploadeService le fait
     * déjà pour un document uploadé directement dans un projet privé.
     */
    @Transactional
    public DocumentDetailDto modifierProjetDocument(
        UUID documentId, Long nouveauProjetId, boolean fusionnerGroupes, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);

        boolean autorise = estEditeur(user) && getUtilisateursAyantAcces(doc).stream()
            .anyMatch(u -> u.getId().equals(user.getId()));
        if (!autorise)
        {
            throw new BusinessException(
                "Seul un éditeur ayant accès à ce document peut le rattacher ou le détacher d'un projet");
        }

        Projet ancien = doc.getProjet();

        if (nouveauProjetId == null)
        {
            // Si ce document partage encore le GroupeAccess de son projet
            // actuel (fusion antérieure, ou héritage direct à l'upload — voir
            // DocumentUploadeService), le détachement doit rompre ce lien
            // permanent : sans ça, le document resterait indéfiniment
            // exposé à quiconque rejoint PLUS TARD le groupe du projet,
            // alors qu'il n'en fait plus partie. On lui donne donc sa PROPRE
            // copie indépendante — figée aux membres actuels, jamais plus
            // suivie par le projet ensuite. Le document reste privé, avec
            // exactement les mêmes personnes qui y avaient accès juste avant.
            // Détail resté silencieux dans le journal — l'entrée
            // DOCUMENT_PROJET_MODIFIE plus bas couvre déjà "détaché du
            // projet X" ; ce dédoublement de groupe n'est qu'un détail de
            // mise en œuvre protégeant l'accès, pas un événement à part.
            if (ancien != null && ancien.getGroupe() != null && doc.getGroupe() != null
                && doc.getGroupe().getId().equals(ancien.getGroupe().getId()))
            {
                GroupeAccess copie = new GroupeAccess();
                copie.setMembres(new ArrayList<>(ancien.getGroupe().getMembres()));
                copie.setCreateAt(LocalDate.now());
                copie = groupeAccessRepository.save(copie);
                doc.setGroupe(copie);
            }

            doc.setProjet(null);
        }
        else
        {
            Projet nouveau = projetRepository.findById(nouveauProjetId)
                .orElseThrow(() -> new BusinessException("Projet introuvable : " + nouveauProjetId));

            if (doc.getUniteOrganisationnelle() == null || nouveau.getUniteOrganisationnelle() == null
                || !nouveau.getUniteOrganisationnelle().getId().equals(doc.getUniteOrganisationnelle().getId()))
            {
                throw new BusinessException(
                    "Ce projet n'appartient pas à la même unité organisationnelle que le document");
            }

            if (nouveau.getAccess() == TypeAccess.PRIVE)
            {
                boolean estMembreDuProjet = nouveau.getGroupe() != null
                    && nouveau.getGroupe().getMembres().stream()
                        .anyMatch(m -> m.getId().equals(user.getId()));
                if (!estMembreDuProjet)
                {
                    throw new BusinessException(
                        "Ce projet est privé — seul un membre de son groupe d'accès peut y rattacher un document");
                }
            }

            // Import automatique du type dans les types attendus du projet
            // CIBLE, si absent — sans quoi le document rattaché n'aurait
            // aucun dossier sous lequel apparaître en le parcourant (voir
            // le javadoc ci-dessus). Ne s'applique qu'au projet cible : un
            // simple changement de projet n'a pas à modifier l'ancien.
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
                projetRepository.save(nouveau);

                auditLogService.log(user, AuditAction.PROJET_TYPES_AJOUTES, AuditCible.PROJET,
                    nouveau.getId().toString(),
                    nouveau.getUniteOrganisationnelle() != null ? nouveau.getUniteOrganisationnelle().getId() : null,
                    "Type \"" + type.getNom() + "\" ajouté automatiquement au projet " + nouveau.getNom()
                        + " (document \"" + doc.getTitre() + "\" rattaché)",
                    true);
            }

            // Document privé rattaché à un projet privé : deux groupes
            // potentiellement différents (voir le javadoc ci-dessus). On ne
            // fusionne jamais sans confirmation explicite du client.
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
                            "Le groupe du document et celui du projet n'ont pas les mêmes membres — "
                            + "confirmation requise avant de les fusionner (voir verifierFusionGroupe)");
                    }

                    GroupeAccess groupeProjet = nouveau.getGroupe();
                    List<User> membresFusionnes = new ArrayList<>(groupeProjet.getMembres());
                    membresFusionnes.addAll(manquants);
                    groupeProjet.setMembres(membresFusionnes);
                    groupeAccessRepository.save(groupeProjet);

                    auditLogService.log(user, AuditAction.GROUPE_MEMBRE_AJOUTE, AuditCible.DOCUMENT,
                        doc.getId().toString(),
                        doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
                        manquants.size() + " membre(s) du groupe du document \"" + doc.getTitre()
                            + "\" fusionné(s) dans le groupe du projet " + nouveau.getNom()
                            + " (rattachement confirmé par l'éditeur)",
                        true);
                }
                // Lien permanent — pas une copie : le document partage désormais
                // le même GroupeAccess que le projet, comme à l'upload direct
                // dans un projet privé (voir DocumentUploadeService).
                doc.setGroupe(nouveau.getGroupe());
            }

            doc.setProjet(nouveau);
        }

        documentRepository.save(doc);

        auditLogService.log(user, AuditAction.DOCUMENT_PROJET_MODIFIE, AuditCible.DOCUMENT,
            doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Projet du document \"" + doc.getTitre() + "\" changé de "
                + (ancien != null ? "\"" + ancien.getNom() + "\"" : "aucun") + " vers "
                + (doc.getProjet() != null ? "\"" + doc.getProjet().getNom() + "\"" : "aucun"),
            true);

        return getDetail(documentId, userDetails);
    }

    /**
     * Appelée par le client AVANT modifierProjetDocument, pour savoir s'il
     * faut avertir l'éditeur qu'une fusion de groupes aura lieu — voir le
     * javadoc de modifierProjetDocument. Lecture seule, aucun effet de bord.
     * groupesDifferents reste false (aucun avertissement) si le document
     * n'est pas privé, si le projet cible ne l'est pas, ou si les deux
     * groupes ont déjà exactement les mêmes membres.
     */
    @Transactional(readOnly = true)
    public FusionGroupeCheckDto verifierFusionGroupe(UUID documentId, Long projetId, UserDetails userDetails)
    {
        User user    = resolveUser(userDetails);
        Document doc = resolveDocument(documentId, user);
        Projet projet = projetRepository.findById(projetId)
            .orElseThrow(() -> new BusinessException("Projet introuvable : " + projetId));

        if (doc.getAccess() != TypeAccess.PRIVE || doc.getGroupe() == null
            || projet.getAccess() != TypeAccess.PRIVE || projet.getGroupe() == null
            || doc.getGroupe().getId().equals(projet.getGroupe().getId()))
        {
            return FusionGroupeCheckDto.builder().groupesDifferents(false).build();
        }

        List<User> manquants = membresManquants(doc.getGroupe(), projet.getGroupe());
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

    // ═══════════════════════════════════════════════════════════════════
    // 9. MÉTADONNÉES — modification après coup (valeurs uniquement, jamais
    //    le fichier/titre/type)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Remplace les valeurs de métadonnées d'un document — même règle
     * d'autorisation que envoyerCorbeille/modifierEmplacementPhysique :
     * réservé à un ÉDITEUR de la liste d'accès normale du document. Ne
     * touche jamais au fichier, au titre ni au type — uniquement les
     * DataType associés.
     *
     * Effet de bord important : si ce document est le SEUL document vivant
     * de son type, les regex d'extraction OCR de ce type (si déjà générées)
     * ont forcément été apprises à partir de CE document, et de rien
     * d'autre — corriger ses métadonnées ici invalide donc automatiquement
     * ces regex (voir TypeDocumentService.viderRegexAutomatiquement),
     * plutôt que de laisser un admin découvrir plus tard, sans lien
     * évident, que les suggestions restent mauvaises pour tout le monde.
     */
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

        // Tous les champs obligatoires doivent être couverts par la requête,
        // même ceux absents de nouvellesValeurs (pas seulement ceux présents
        // avec une valeur vide) — sinon un client pourrait simplement omettre
        // un champ obligatoire pour contourner la validation ci-dessus.
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

    /**
     * Résout un document en vérifiant que l'utilisateur y a droit :
     *   - l'uploadeur a toujours accès, quel que soit le statut ;
     *   - sinon, pour un document SAIN, quiconque a normalement accès (public
     *     de son UO, ou membre du groupe privé) — quel que soit son rôle, voir
     *     estVisibleNormalement ; c'est ce qui permet à un simple USER de
     *     consulter/télécharger un document pour lequel il est autorisé, pas
     *     seulement à son propre uploadeur (ROUTE FINALE de "USER consulte et
     *     télécharge les documents auxquels il est autorisé") ;
     *   - sinon, pour un document CORROMPU ou en CORBEILLE, restreint à
     *     l'ADMIN/ADMIN_UO ayant autorité sur son UO (lecture — investigation
     *     ou audit de la corbeille), ou à un ÉDITEUR de sa liste d'accès (voir
     *     getUtilisateursAyantAcces) — qui peut en plus le restaurer/remplacer
     *     (voir envoyerCorbeille/restaurerDepuisCorbeille/FixityCheckService) ;
     *     un simple USER ne le voit plus dans aucun des deux cas, même s'il y
     *     avait normalement accès.
     */
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