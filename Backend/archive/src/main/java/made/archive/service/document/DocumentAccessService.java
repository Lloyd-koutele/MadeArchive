package made.archive.service.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.MeilisearchProperties;
import made.archive.dto.DocumentAccessFilterDto;
import made.archive.dto.DocumentListItemDto;
import made.archive.dto.DocumentPageDto;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeAccess;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.UserRepository;
import made.archive.service.organisation.UniteOrganisationnelleService;
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

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAccessService
{
    private static final String INDEX_NAME = "documents";

    private final DocumentRepository documentRepository;
    private final UserRepository     userRepository;
    private final UniteOrganisationnelleService uniteOrganisationnelleService;
    private final WebClient.Builder webClientBuilder;
    private final MeilisearchProperties meilisearchProperties;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DocumentPageDto getDocumentsAccessibles(
        DocumentAccessFilterDto filter,
        UserDetails userDetails)
    {
        User user = resolveUser(userDetails);

        Pageable pageable = PageRequest.of(
            Math.max(0, filter.getPage() - 1),
            Math.min(filter.getSize(), 50),
            Sort.by(Sort.Direction.DESC, "createAt")
        );

        // null = ADMIN global, aucun filtrage UO nécessaire — voir
        // UniteOrganisationnelleService.getUoIdsVisiblesPourLecture().
        java.util.Set<Long> uoVisibles = uniteOrganisationnelleService.getUoIdsVisiblesPourLecture(user);

        // Recherche plein texte (titre + CONTENU OCR + métadonnées, via
        // Meilisearch) — calculée une seule fois ici, en dehors du lambda de
        // la Specification (qui peut être évalué plusieurs fois par Hibernate
        // — un appel réseau dedans serait répété inutilement). null = pas de
        // requête, ou Meilisearch indisponible → repli sur le titre seul,
        // voir buildSpecification.
        Set<UUID> idsPleinTexte = (filter.getTitre() != null && !filter.getTitre().isBlank())
            ? rechercherIdsMeilisearch(filter.getTitre().trim())
            : null;

        Specification<Document> spec = buildSpecification(filter, user, uoVisibles, idsPleinTexte);

        Page<Document> pageResult = documentRepository.findAll(spec, pageable);

        List<DocumentListItemDto> items = pageResult.getContent().stream()
            .map(doc -> toDto(doc, user))
            .collect(Collectors.toList());

        log.info("[Access] {} résultat(s) pour {} avec filtres: titre={}, access={}, debut={}, fin={}",
            pageResult.getTotalElements(),
            user.getEmail(),
            filter.getTitre(),
            filter.getAccess(),
            filter.getDateDebut(),
            filter.getDateFin());

        return DocumentPageDto.builder()
            .content(items)
            .page(filter.getPage())
            .size(filter.getSize())
            .totalElements(pageResult.getTotalElements())
            .totalPages(pageResult.getTotalPages())
            .build();
    }

    private Specification<Document> buildSpecification(
        DocumentAccessFilterDto filter,
        User user,
        java.util.Set<Long> uoVisibles,
        Set<UUID> idsPleinTexte)
    {
        return (root, query, cb) ->
        {
            List<Predicate> predicates = new ArrayList<>();

            // ── 1. Exclure les documents supprimés ET ceux en corbeille —
            //      voir DocumentAccessService.getDocumentsCorbeille pour la
            //      seule vue qui les montre.
            predicates.add(root.get("status").in(DocumentStatus.DELETED, DocumentStatus.CORBEILLE).not());

            // ── 1b. Périmètre UO — null (ADMIN) = pas de filtrage ; sinon un
            //       document PUBLIC d'une UO totalement étrangère à l'acteur
            //       ne doit jamais apparaître ici (voir aussi DocumentSearchService,
            //       même règle appliquée côté Meilisearch). Ensemble vide = aucun
            //       document visible plutôt qu'un IN () invalide en SQL.
            if (uoVisibles != null)
            {
                if (uoVisibles.isEmpty())
                {
                    predicates.add(cb.disjunction());
                }
                else
                {
                    predicates.add(root.get("uniteOrganisationnelle").get("id").in(uoVisibles));
                }
            }

            // ── 2. Chip de filtre PUBLIC/PRIVE demandé par l'utilisateur — un
            //      simple filtre d'affichage, orthogonal à la visibilité réelle
            //      (sécurité) calculée juste en dessous (2b).
            if ("PUBLIC".equalsIgnoreCase(filter.getAccess()))
            {
                predicates.add(cb.equal(root.get("access"), TypeAccess.PUBLIC));
            }
            else if ("PRIVE".equalsIgnoreCase(filter.getAccess()))
            {
                predicates.add(cb.equal(root.get("access"), TypeAccess.PRIVE));
            }

            // ── 2b. Visibilité réelle : PUBLIC ou membre du groupe PRIVÉ —
            //       SAUF pour un document CORROMPU, où la règle change : voir
            //       DocumentService.getUtilisateursAyantAcces / resolveDocument.
            //       Un CORROMPU n'est visible que des ADMIN/ADMIN_UO ayant
            //       autorité (déjà garanti par le périmètre UO en 1b — voir
            //       estAdminOuAdminUO) et des ÉDITEURS y ayant accès (qui
            //       doivent donc, eux, toujours satisfaire la règle normale
            //       PUBLIC/membre) ; un simple USER ne le voit plus du tout,
            //       même s'il y avait normalement accès.
            Predicate isPublic = cb.equal(root.get("access"), TypeAccess.PUBLIC);

            Join<Object, Object> groupe  = root.join("groupe",   JoinType.LEFT);
            Join<Object, Object> membres = groupe.join("membres", JoinType.LEFT);
            Predicate estMembre = cb.and(
                cb.equal(root.get("access"), TypeAccess.PRIVE),
                cb.equal(membres.get("id"), user.getId())
            );
            Predicate accesNormal = cb.or(isPublic, estMembre);

            boolean estAdminOuAdminUO = user.getRoles().stream()
                .anyMatch(r -> r.getName() == Role_Name.ADMIN || r.getName() == Role_Name.ADMIN_UO);
            boolean estEditeur = user.getRoles().stream()
                .anyMatch(r -> r.getName() == Role_Name.EDITOR);

            Predicate visibiliteSiCorrompu = estAdminOuAdminUO
                ? cb.conjunction()
                : (estEditeur ? accesNormal : cb.disjunction());

            predicates.add(cb.or(
                cb.and(cb.notEqual(root.get("status"), DocumentStatus.CORRUPTED), accesNormal),
                cb.and(cb.equal(root.get("status"), DocumentStatus.CORRUPTED), visibiliteSiCorrompu)
            ));

            // ── 3. Filtre plein texte — titre (LIKE, toujours disponible) OU
            //      contenu/métadonnées (Meilisearch, voir idsPleinTexte
            //      calculé une seule fois avant l'appel à cette méthode).
            //      idsPleinTexte == null : requête vide, ou Meilisearch
            //      indisponible → repli sur le titre seul, jamais un "aucun
            //      résultat" silencieux à cause d'une panne de Meilisearch.
            if (filter.getTitre() != null && !filter.getTitre().isBlank())
            {
                Predicate titreLike = cb.like(
                    cb.lower(root.get("titre")),
                    "%" + filter.getTitre().trim().toLowerCase() + "%"
                );

                if (idsPleinTexte != null && !idsPleinTexte.isEmpty())
                {
                    predicates.add(cb.or(titreLike, root.get("id").in(idsPleinTexte)));
                }
                else
                {
                    predicates.add(titreLike);
                }
            }

            // ── 3b. Restriction UO explicite (navigation Admin/Admin_UO) ──
            //       Combinée en AND avec le prédicat de périmètre ci-dessus :
            //       demander une UO hors périmètre ne peut jamais rien renvoyer.
            if (filter.getUoId() != null)
            {
                predicates.add(cb.equal(
                    root.get("uniteOrganisationnelle").get("id"),
                    filter.getUoId()
                ));
            }

            // ── 4. Filtre par type de document ────────────────────────────
            if (filter.getTypeDocumentId() != null)
            {
                predicates.add(cb.equal(
                    root.get("typeDocument").get("id"),
                    filter.getTypeDocumentId()
                ));
            }

            // ── 4b. Filtre par projet — ne contourne jamais la visibilité
            //       réelle calculée en 2b, seulement une restriction de plus.
            if (filter.getProjetId() != null)
            {
                predicates.add(cb.equal(
                    root.get("projet").get("id"),
                    filter.getProjetId()
                ));
            }

            // ── 5. Filtre par date d'archivage (createAt) ─────────────────
            if (filter.getDateDebut() != null)
            {
                LocalDateTime debut = filter.getDateDebut().atStartOfDay();
                predicates.add(cb.greaterThanOrEqualTo(root.get("createAt"), debut));
            }
            if (filter.getDateFin() != null)
            {
                LocalDateTime fin = filter.getDateFin().atTime(LocalTime.MAX);
                predicates.add(cb.lessThanOrEqualTo(root.get("createAt"), fin));
            }

            // ── 6. Filtre par statut ──────────────────────────────────────
            if (filter.getStatut() != null && !filter.getStatut().isBlank())
            {
                try
                {
                    DocumentStatus statut = DocumentStatus.valueOf(filter.getStatut());
                    predicates.add(cb.equal(root.get("status"), statut));
                }
                catch (IllegalArgumentException e)
                {
                    log.warn("[Access] Statut invalide ignoré : {}", filter.getStatut());
                }
            }

            // Éviter les doublons causés par la jointure LEFT sur membres
            query.distinct(true);

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // Corbeille — voir DocumentService.envoyerCorbeille/restaurerDepuisCorbeille
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Documents en CORBEILLE visibles par l'utilisateur connecté :
     *   - ADMIN : toute la corbeille, aucune restriction (uoVisibles null) ;
     *   - ADMIN_UO : la corbeille de son UO + descendantes, LECTURE SEULE
     *     (le contrôle d'écriture — restaurer — se fait dans
     *     DocumentService.restaurerDepuisCorbeille, réservé aux éditeurs,
     *     jamais ouvert ici même à ADMIN_UO) ;
     *   - ÉDITEUR (ni ADMIN ni ADMIN_UO) : uniquement les documents auxquels
     *     il a normalement accès (public de son UO, membre du groupe privé,
     *     ou lui-même l'uploadeur) — même périmètre que
     *     getUtilisateursAyantAcces côté DocumentService, en pur JPA ici.
     * Fermé à ROLE_USER simple par le contrôleur (@Secured) — jamais atteint
     * par ce chemin.
     */
    @Transactional(readOnly = true)
    public DocumentPageDto getDocumentsCorbeille(int page, int size, UserDetails userDetails)
    {
        User user = resolveUser(userDetails);

        Pageable pageable = PageRequest.of(
            Math.max(0, page - 1),
            Math.min(size, 50),
            Sort.by(Sort.Direction.DESC, "createAt")
        );

        java.util.Set<Long> uoVisibles = uniteOrganisationnelleService.getUoIdsVisiblesPourLecture(user);
        boolean estAdminOuAdminUO = user.getRoles().stream()
            .anyMatch(r -> r.getName() == Role_Name.ADMIN || r.getName() == Role_Name.ADMIN_UO);

        Specification<Document> spec = (root, query, cb) ->
        {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), DocumentStatus.CORBEILLE));

            if (uoVisibles != null)
            {
                if (uoVisibles.isEmpty())
                {
                    predicates.add(cb.disjunction());
                }
                else
                {
                    predicates.add(root.get("uniteOrganisationnelle").get("id").in(uoVisibles));
                }
            }

            if (!estAdminOuAdminUO)
            {
                Predicate isPublic = cb.equal(root.get("access"), TypeAccess.PUBLIC);
                Predicate estUploadeur = cb.equal(root.get("uploadedBy").get("id"), user.getId());

                Join<Object, Object> groupe  = root.join("groupe",   JoinType.LEFT);
                Join<Object, Object> membres = groupe.join("membres", JoinType.LEFT);
                Predicate estMembre = cb.and(
                    cb.equal(root.get("access"), TypeAccess.PRIVE),
                    cb.equal(membres.get("id"), user.getId())
                );

                predicates.add(cb.or(isPublic, estMembre, estUploadeur));
            }

            query.distinct(true);
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Document> pageResult = documentRepository.findAll(spec, pageable);

        List<DocumentListItemDto> items = pageResult.getContent().stream()
            .map(doc -> toDto(doc, user))
            .collect(Collectors.toList());

        return DocumentPageDto.builder()
            .content(items)
            .page(page)
            .size(size)
            .totalElements(pageResult.getTotalElements())
            .totalPages(pageResult.getTotalPages())
            .build();
    }

    private User resolveUser(UserDetails userDetails)
    {
        return userRepository.findByEmail(userDetails.getUsername())
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));
    }

    /**
     * IDs des documents dont le TITRE, le CONTENU (texte OCR) ou une
     * MÉTADONNÉE correspond à la requête — voir MeilisearchDocumentDto,
     * tous ces champs sont indexés et recherchables par défaut. Combiné en
     * OR avec le LIKE sur le titre par buildSpecification, jamais utilisé
     * seul : la visibilité réelle (public/privé/UO/corrompu) reste calculée
     * en JPA juste après, comme pour tout le reste de cette classe — un hit
     * Meilisearch ne donne jamais accès à un document normalement invisible.
     *
     * @return null si la requête Meilisearch échoue (indisponible, timeout...)
     *         — signal explicite "indisponible" pour que l'appelant retombe
     *         sur le titre seul plutôt que de renvoyer zéro résultat à cause
     *         d'une panne de Meilisearch. Un ensemble vide, en revanche,
     *         signifie "interrogé avec succès, aucun résultat".
     */
    private Set<UUID> rechercherIdsMeilisearch(String query)
    {
        try
        {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("q", query);
            // Une centaine de hits suffit largement à couvrir une page —
            // le filtrage de visibilité et la pagination réels restent de
            // toute façon appliqués juste après, côté BD.
            body.put("limit", 200);
            body.put("attributesToRetrieve", List.of("id"));
            body.put("filter", "status != DELETED AND status != CORBEILLE");

            WebClient client = webClientBuilder
                .baseUrl(meilisearchProperties.getHost())
                .defaultHeader("Authorization", "Bearer " + meilisearchProperties.getSearchKey())
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

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hits = response.get("hits") instanceof List<?>
                ? (List<Map<String, Object>>) response.get("hits")
                : List.of();

            return hits.stream()
                .map(h -> (String) h.get("id"))
                .filter(Objects::nonNull)
                .map(this::parseUuidOuNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        }
        catch (Exception e)
        {
            log.warn("[Access] Recherche plein texte Meilisearch indisponible pour '{}', repli sur le titre seul : {}",
                query, e.getMessage());
            return null;
        }
    }

    private UUID parseUuidOuNull(String s)
    {
        try
        {
            return UUID.fromString(s);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private DocumentListItemDto toDto(Document doc, User currentUser)
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
            .statutAvantCorbeille(doc.getStatutAvantCorbeille() != null ? doc.getStatutAvantCorbeille().name() : null)
            .suppressionPrevueLe(doc.getSuppressionPrevueLe())
            .peutGererCorbeille(peutGererCorbeille(doc, currentUser))
            .build();
    }

    /**
     * Duplique volontairement DocumentService.getUtilisateursAyantAcces —
     * pas de dépendance croisée entre les deux services (voir le commentaire
     * sur AuditLogService plus haut dans ce fichier, même principe).
     */
    private boolean peutGererCorbeille(Document doc, User currentUser)
    {
        boolean estEditeur = currentUser.getRoles().stream()
            .anyMatch(r -> r.getName() == Role_Name.EDITOR);
        if (!estEditeur)
        {
            return false;
        }

        List<User> ayantAcces = doc.getAccess() == TypeAccess.PRIVE
            ? (doc.getGroupe() != null ? doc.getGroupe().getMembres() : List.of(doc.getUploadedBy()))
            : (doc.getUniteOrganisationnelle() != null
                ? userRepository.findByUniteOrganisationnelleId(doc.getUniteOrganisationnelle().getId())
                : List.of(doc.getUploadedBy()));

        return ayantAcces.stream().anyMatch(u -> u.getId().equals(currentUser.getId()));
    }
}