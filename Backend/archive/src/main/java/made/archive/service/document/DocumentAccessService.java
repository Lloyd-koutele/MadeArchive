package made.archive.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAccessService
{
    private final DocumentRepository documentRepository;
    private final UserRepository     userRepository;
    private final UniteOrganisationnelleService uniteOrganisationnelleService;

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

        Specification<Document> spec = buildSpecification(filter, user, uoVisibles);

        Page<Document> pageResult = documentRepository.findAll(spec, pageable);

        List<DocumentListItemDto> items = pageResult.getContent().stream()
            .map(this::toDto)
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
        java.util.Set<Long> uoVisibles)
    {
        return (root, query, cb) ->
        {
            List<Predicate> predicates = new ArrayList<>();

            // ── 1. Exclure les documents supprimés ────────────────────────
            predicates.add(cb.notEqual(root.get("status"), DocumentStatus.DELETED));

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

            // ── 3. Filtre par titre (LIKE insensible à la casse) ──────────
            if (filter.getTitre() != null && !filter.getTitre().isBlank())
            {
                predicates.add(cb.like(
                    cb.lower(root.get("titre")),
                    "%" + filter.getTitre().trim().toLowerCase() + "%"
                ));
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

    private User resolveUser(UserDetails userDetails)
    {
        return userRepository.findByEmail(userDetails.getUsername())
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));
    }

    private DocumentListItemDto toDto(Document doc)
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
}