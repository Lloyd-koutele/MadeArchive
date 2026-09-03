package made.archive.service.organisation;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.PhysicalLocationCreateDto;
import made.archive.dto.PhysicalLocationDto;
import made.archive.dto.PhysicalLocationNodeDto;
import made.archive.dto.PhysicalLocationUpdateDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.LocationStatus;
import made.archive.entite.PhysicalLocation;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.PhysicalLocationRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.service.audit.AuditLogService;

/**
 * Localisation physique des originaux papier — voir PhysicalLocation.
 *
 * Arbre entièrement libre par UO (pas de LocationType en enum, chaque UO
 * construit sa propre arborescence). Gestion (créer/modifier/changer le
 * type/désactiver/réactiver/supprimer) réservée à ADMIN (partout) et
 * ADMIN_UO (seulement sur leur UO et ses UO descendantes — voir
 * UniteOrganisationnelleService.aAutoriteSur, même condition que pour la
 * gestion des UO elles-mêmes).
 *
 * Règles structurelles (voir Javadoc de PhysicalLocation) :
 *   - storagePoint=true (point de stockage) : peut recevoir des documents,
 *     jamais d'enfant.
 *   - storagePoint=false (nœud chemin) : peut avoir des enfants, ne reçoit
 *     jamais directement de document.
 *   - Le type d'un nœud n'est modifiable QUE si le nœud est "vide" (aucun
 *     document vivant rattaché), et pour devenir storagePoint=true il doit
 *     aussi n'avoir aucun enfant.
 *   - Désactiver un nœud cascade automatiquement l'INACTIVE à TOUTE sa
 *     sous-arborescence (jamais aux nœuds frères). Réactiver ne cascade PAS
 *     (un enfant peut avoir été désactivé pour sa propre raison) et est
 *     refusé tant qu'un ancêtre reste INACTIVE (éviterait un nœud "actif"
 *     sous une branche fermée).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhysicalLocationService
{
    private final PhysicalLocationRepository locationRepository;
    private final UniteOrganisationnelleRepository uoRepository;
    private final DocumentRepository documentRepository;
    private final UniteOrganisationnelleService uoService;
    private final AuditLogService auditLogService;

    // ═══════════════════════════════════════════════════════════════════
    // Écriture
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public PhysicalLocationDto creer(PhysicalLocationCreateDto dto, User currentUser)
    {
        if (dto.getUniteOrganisationnelleId() == null)
        {
            throw new BusinessException("L'unité organisationnelle est obligatoire");
        }
        if (dto.getName() == null || dto.getName().isBlank())
        {
            throw new BusinessException("Le nom est obligatoire");
        }

        if (!uoService.aAutoriteSur(dto.getUniteOrganisationnelleId(), currentUser))
        {
            throw new AccessDeniedException(
                "Vous devez être ADMIN, ou ADMIN_UO ayant autorité sur cette UO, pour y créer un emplacement");
        }

        UniteOrganisationnelle uo = uoRepository.findById(dto.getUniteOrganisationnelleId())
            .orElseThrow(() -> new BusinessException("UO introuvable : " + dto.getUniteOrganisationnelleId()));

        PhysicalLocation parent = null;
        if (dto.getParentId() != null)
        {
            parent = locationRepository.findById(dto.getParentId())
                .orElseThrow(() -> new BusinessException("Emplacement parent introuvable"));

            if (parent.isStoragePoint())
            {
                throw new BusinessException(
                    "Impossible : \"" + parent.getName() + "\" est un point de stockage, il ne peut pas avoir d'enfant");
            }
            if (parent.getStatus() != LocationStatus.ACTIVE)
            {
                throw new BusinessException(
                    "Impossible : \"" + parent.getName() + "\" est désactivé");
            }
            if (!parent.getUniteOrganisationnelle().getId().equals(dto.getUniteOrganisationnelleId()))
            {
                throw new BusinessException(
                    "L'emplacement enfant doit appartenir à la même UO que son parent");
            }
        }

        PhysicalLocation loc = new PhysicalLocation();
        loc.setName(dto.getName());
        loc.setDescription(dto.getDescription());
        loc.setStoragePoint(dto.isStoragePoint());
        loc.setParent(parent);
        loc.setUniteOrganisationnelle(uo);
        loc.setStatus(LocationStatus.ACTIVE);
        loc.setCreatedBy(currentUser);
        loc.setCreatedAt(LocalDateTime.now());

        PhysicalLocation saved = locationRepository.save(loc);

        auditLogService.log(currentUser, AuditAction.LOCATION_CREEE, AuditCible.PHYSICAL_LOCATION,
            saved.getId().toString(), uo.getId(),
            "Création de l'emplacement \"" + saved.getName() + "\" ("
                + (saved.isStoragePoint() ? "point de stockage" : "chemin") + ")"
                + (parent != null ? " sous \"" + parent.getName() + "\"" : " (racine)"),
            true);

        return toDto(saved);
    }

    /**
     * Déplace un emplacement (glisser-déposer) — change son parent, ou le
     * fait devenir une nouvelle racine si nouveauParentId est null.
     *
     * L'autorité est vérifiée sur l'UO de loc, qui est aussi celle exigée du
     * nouveau parent (arbre scopé par UO — voir Javadoc de classe), donc une
     * seule vérification suffit ici.
     *
     * Refusé si :
     *   - la cible est le nœud lui-même ou l'un de ses propres descendants
     *     (créerait un cycle) ;
     *   - la cible est un point de stockage (ne peut jamais avoir d'enfant) ;
     *   - la cible est désactivée — même incohérence que réactiver un nœud
     *     sous un ancêtre INACTIVE, refusée pour la même raison : jamais de
     *     nœud "actif" sous une branche fermée.
     */
    @Transactional
    public PhysicalLocationDto deplacer(UUID id, UUID nouveauParentId, User currentUser)
    {
        PhysicalLocation loc = getEtVerifierAutorite(id, currentUser);

        PhysicalLocation nouveauParent = null;
        if (nouveauParentId != null)
        {
            if (nouveauParentId.equals(id))
            {
                throw new BusinessException("Un emplacement ne peut pas être son propre parent");
            }

            nouveauParent = locationRepository.findById(nouveauParentId)
                .orElseThrow(() -> new BusinessException("Emplacement cible introuvable"));

            if (estLuiMemeOuDescendantDe(nouveauParent, loc))
            {
                throw new BusinessException(
                    "Impossible : cet emplacement ne peut pas devenir enfant de l'un de ses propres descendants");
            }
            if (nouveauParent.isStoragePoint())
            {
                throw new BusinessException(
                    "Impossible : \"" + nouveauParent.getName() + "\" est un point de stockage, il ne peut pas avoir d'enfant");
            }
            if (nouveauParent.getStatus() != LocationStatus.ACTIVE)
            {
                throw new BusinessException(
                    "Impossible : \"" + nouveauParent.getName() + "\" est désactivé");
            }
            if (!nouveauParent.getUniteOrganisationnelle().getId().equals(loc.getUniteOrganisationnelle().getId()))
            {
                throw new BusinessException("L'emplacement cible doit appartenir à la même UO");
            }
        }

        UUID ancienParentId = loc.getParent() != null ? loc.getParent().getId() : null;
        if ((ancienParentId == null && nouveauParentId == null)
            || (ancienParentId != null && ancienParentId.equals(nouveauParentId)))
        {
            return toDto(loc);
        }

        loc.setParent(nouveauParent);
        loc.setUpdatedBy(currentUser);
        loc.setUpdatedAt(LocalDateTime.now());
        PhysicalLocation saved = locationRepository.save(loc);

        auditLogService.log(currentUser, AuditAction.LOCATION_DEPLACEE, AuditCible.PHYSICAL_LOCATION,
            saved.getId().toString(), saved.getUniteOrganisationnelle().getId(),
            "Déplacement de \"" + saved.getName() + "\" "
                + (nouveauParent != null ? "sous \"" + nouveauParent.getName() + "\"" : "vers la racine"),
            true);

        return toDto(saved);
    }

    @Transactional
    public PhysicalLocationDto modifier(UUID id, PhysicalLocationUpdateDto dto, User currentUser)
    {
        PhysicalLocation loc = getEtVerifierAutorite(id, currentUser);

        if (dto.getName() != null && !dto.getName().isBlank())
        {
            loc.setName(dto.getName());
        }
        if (dto.getDescription() != null)
        {
            loc.setDescription(dto.getDescription());
        }
        loc.setUpdatedBy(currentUser);
        loc.setUpdatedAt(LocalDateTime.now());

        PhysicalLocation saved = locationRepository.save(loc);

        auditLogService.log(currentUser, AuditAction.LOCATION_MODIFIEE, AuditCible.PHYSICAL_LOCATION,
            saved.getId().toString(), saved.getUniteOrganisationnelle().getId(),
            "Modification de l'emplacement \"" + saved.getName() + "\"", true);

        return toDto(saved);
    }

    @Transactional
    public PhysicalLocationDto changerTypeStockage(UUID id, boolean nouveauStoragePoint, User currentUser)
    {
        PhysicalLocation loc = getEtVerifierAutorite(id, currentUser);

        if (loc.isStoragePoint() == nouveauStoragePoint)
        {
            return toDto(loc);
        }

        if (documentRepository.existsByPhysicalLocationIdAndStatusNot(id, DocumentStatus.DELETED))
        {
            throw new BusinessException(
                "Impossible de changer le type : des documents sont rattachés à cet emplacement");
        }
        if (nouveauStoragePoint && !locationRepository.findByParentId(id).isEmpty())
        {
            throw new BusinessException(
                "Impossible de devenir un point de stockage : cet emplacement a des enfants");
        }

        loc.setStoragePoint(nouveauStoragePoint);
        loc.setUpdatedBy(currentUser);
        loc.setUpdatedAt(LocalDateTime.now());
        PhysicalLocation saved = locationRepository.save(loc);

        auditLogService.log(currentUser, AuditAction.LOCATION_TYPE_CHANGE, AuditCible.PHYSICAL_LOCATION,
            saved.getId().toString(), saved.getUniteOrganisationnelle().getId(),
            "Emplacement \"" + saved.getName() + "\" devient "
                + (nouveauStoragePoint ? "point de stockage" : "nœud chemin"), true);

        return toDto(saved);
    }

    /**
     * Désactive un emplacement ET toute sa sous-arborescence (jamais ses
     * frères) — voir Javadoc de classe.
     */
    @Transactional
    public PhysicalLocationDto desactiver(UUID id, User currentUser)
    {
        PhysicalLocation loc = getEtVerifierAutorite(id, currentUser);

        List<PhysicalLocation> sousArbre = collecterSousArbre(loc);
        LocalDateTime maintenant = LocalDateTime.now();
        for (PhysicalLocation n : sousArbre)
        {
            n.setStatus(LocationStatus.INACTIVE);
            n.setUpdatedBy(currentUser);
            n.setUpdatedAt(maintenant);
        }
        locationRepository.saveAll(sousArbre);

        auditLogService.log(currentUser, AuditAction.LOCATION_DESACTIVEE, AuditCible.PHYSICAL_LOCATION,
            loc.getId().toString(), loc.getUniteOrganisationnelle().getId(),
            "Désactivation de \"" + loc.getName() + "\" et de " + (sousArbre.size() - 1)
                + " emplacement(s) descendant(s)", true);

        return toDto(loc);
    }

    /**
     * Réactive UN SEUL emplacement (pas de cascade vers les enfants — ils
     * ont pu être désactivés indépendamment). Refusé tant qu'un ancêtre
     * reste INACTIVE, pour ne jamais laisser un nœud "actif" au milieu d'une
     * branche fermée.
     */
    @Transactional
    public PhysicalLocationDto reactiver(UUID id, User currentUser)
    {
        PhysicalLocation loc = getEtVerifierAutorite(id, currentUser);

        PhysicalLocation ancetre = loc.getParent();
        while (ancetre != null)
        {
            if (ancetre.getStatus() != LocationStatus.ACTIVE)
            {
                throw new BusinessException(
                    "Impossible de réactiver : l'ancêtre \"" + ancetre.getName()
                    + "\" est désactivé, réactivez-le d'abord");
            }
            ancetre = ancetre.getParent();
        }

        loc.setStatus(LocationStatus.ACTIVE);
        loc.setUpdatedBy(currentUser);
        loc.setUpdatedAt(LocalDateTime.now());
        PhysicalLocation saved = locationRepository.save(loc);

        auditLogService.log(currentUser, AuditAction.LOCATION_REACTIVEE, AuditCible.PHYSICAL_LOCATION,
            saved.getId().toString(), saved.getUniteOrganisationnelle().getId(),
            "Réactivation de \"" + saved.getName() + "\"", true);

        return toDto(saved);
    }

    /** Suppression définitive — seulement si vide (pas d'enfant, pas de document vivant rattaché). */
    @Transactional
    public void supprimer(UUID id, User currentUser)
    {
        PhysicalLocation loc = getEtVerifierAutorite(id, currentUser);

        // Toute la sous-arborescence (loc incluse) — pas seulement une feuille :
        // supprimer un nœud avec des enfants est autorisé si TOUT (lui-même et
        // chaque descendant) est vide de documents vivants. Statut ACTIVE/
        // INACTIVE indifférent — seule l'absence de document compte, pas
        // besoin d'exiger une désactivation préalable.
        List<PhysicalLocation> sousArbre = collecterSousArbre(loc);

        for (PhysicalLocation n : sousArbre)
        {
            if (documentRepository.existsByPhysicalLocationIdAndStatusNot(n.getId(), DocumentStatus.DELETED))
            {
                throw new BusinessException(
                    "Impossible de supprimer : \"" + n.getName() + "\""
                    + (n.getId().equals(id) ? "" : " (dans la sous-arborescence de \"" + loc.getName() + "\")")
                    + " a des documents rattachés");
            }
        }

        // Enfants d'abord, parent en dernier — collecterSousArbre garantit un
        // parent toujours avant ses enfants dans la liste, donc l'inverser
        // donne l'ordre de suppression sûr vis-à-vis de la contrainte de clé
        // étrangère parent_id (physical_locations → physical_locations).
        List<PhysicalLocation> ordreSuppression = new ArrayList<>(sousArbre);
        Collections.reverse(ordreSuppression);
        locationRepository.deleteAll(ordreSuppression);

        auditLogService.log(currentUser, AuditAction.LOCATION_SUPPRIMEE, AuditCible.PHYSICAL_LOCATION,
            id.toString(), loc.getUniteOrganisationnelle().getId(),
            sousArbre.size() == 1
                ? "Suppression de l'emplacement \"" + loc.getName() + "\""
                : "Suppression de l'emplacement \"" + loc.getName() + "\" et de "
                  + (sousArbre.size() - 1) + " descendant(s)",
            true);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lecture
    // ═══════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public PhysicalLocationDto getById(UUID id, User currentUser)
    {
        PhysicalLocation loc = locationRepository.findById(id)
            .orElseThrow(() -> new BusinessException("Emplacement introuvable : " + id));
        verifierVisiblePourLecture(loc.getUniteOrganisationnelle().getId(), currentUser);
        return toDto(loc);
    }

    /** Arbre complet (tous statuts) d'une UO — reconstruit en mémoire à partir d'un seul SELECT. */
    @Transactional(readOnly = true)
    public List<PhysicalLocationNodeDto> getArbre(Long uoId, User currentUser)
    {
        verifierVisiblePourLecture(uoId, currentUser);

        List<PhysicalLocation> tous = locationRepository.findByUniteOrganisationnelleId(uoId);
        Map<UUID, List<PhysicalLocation>> parEnfantsDe = tous.stream()
            .filter(n -> n.getParent() != null)
            .collect(Collectors.groupingBy(n -> n.getParent().getId()));

        return tous.stream()
            .filter(n -> n.getParent() == null)
            .map(n -> versNode(n, parEnfantsDe))
            .toList();
    }

    /** Emplacements assignables à un document (storagePoint=true, ACTIVE) pour une UO. */
    @Transactional(readOnly = true)
    public List<PhysicalLocationDto> getEmplacementsDisponibles(Long uoId, User currentUser)
    {
        verifierVisiblePourLecture(uoId, currentUser);
        return locationRepository
            .findByUniteOrganisationnelleIdAndStoragePointTrueAndStatus(uoId, LocationStatus.ACTIVE)
            .stream()
            .map(this::toDto)
            .toList();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Utilisé par DocumentService/DocumentUploadeService
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Résout et valide un emplacement pour le rattacher à un document :
     * doit être un point de stockage ACTIF de la MÊME UO que le document.
     */
    @Transactional(readOnly = true)
    public PhysicalLocation resolvePourRattachement(UUID locationId, Document document)
    {
        PhysicalLocation loc = locationRepository.findById(locationId)
            .orElseThrow(() -> new BusinessException("Emplacement introuvable : " + locationId));

        if (!loc.isStoragePoint())
        {
            throw new BusinessException(
                "\"" + loc.getName() + "\" est un nœud chemin, il ne peut pas recevoir de document directement");
        }
        if (loc.getStatus() != LocationStatus.ACTIVE)
        {
            throw new BusinessException("\"" + loc.getName() + "\" est désactivé");
        }
        if (document.getUniteOrganisationnelle() == null
            || !loc.getUniteOrganisationnelle().getId().equals(document.getUniteOrganisationnelle().getId()))
        {
            throw new BusinessException(
                "Cet emplacement n'appartient pas à la même unité organisationnelle que le document");
        }
        return loc;
    }

    /** Chemin complet lisible, ex. "Bâtiment A › Salle 204 › Rayon R03 › Boîte B001". */
    public String construireChemin(PhysicalLocation loc)
    {
        List<String> segments = new ArrayList<>();
        PhysicalLocation courant = loc;
        while (courant != null)
        {
            segments.add(0, courant.getName());
            courant = courant.getParent();
        }
        return String.join(" › ", segments);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    private PhysicalLocation getEtVerifierAutorite(UUID id, User currentUser)
    {
        PhysicalLocation loc = locationRepository.findById(id)
            .orElseThrow(() -> new BusinessException("Emplacement introuvable : " + id));

        if (!uoService.aAutoriteSur(loc.getUniteOrganisationnelle().getId(), currentUser))
        {
            throw new AccessDeniedException("Vous n'avez pas l'autorité sur l'UO de cet emplacement");
        }
        return loc;
    }

    /** true si cible == loc, ou si cible est un descendant de loc — détecte un déplacement cyclique. */
    private boolean estLuiMemeOuDescendantDe(PhysicalLocation cible, PhysicalLocation loc)
    {
        PhysicalLocation courant = cible;
        while (courant != null)
        {
            if (courant.getId().equals(loc.getId()))
            {
                return true;
            }
            courant = courant.getParent();
        }
        return false;
    }

    /**
     * Lecture (browsing/fiche) : plus large que la gestion — tout utilisateur
     * voyant normalement cette UO (même règle que documents/projets, voir
     * UniteOrganisationnelleService.getUoIdsVisiblesPourLecture), pas
     * seulement ceux ayant autorité de gestion dessus.
     */
    private void verifierVisiblePourLecture(Long uoId, User currentUser)
    {
        var uoVisibles = uoService.getUoIdsVisiblesPourLecture(currentUser);
        if (uoVisibles != null && !uoVisibles.contains(uoId))
        {
            throw new AccessDeniedException("Vous n'avez pas accès à cette UO");
        }
    }

    /** Ce nœud + tous ses descendants (BFS), jamais ses frères. */
    private List<PhysicalLocation> collecterSousArbre(PhysicalLocation racine)
    {
        List<PhysicalLocation> resultat = new ArrayList<>();
        Deque<PhysicalLocation> aTraiter = new ArrayDeque<>();
        aTraiter.push(racine);
        while (!aTraiter.isEmpty())
        {
            PhysicalLocation courant = aTraiter.pop();
            resultat.add(courant);
            locationRepository.findByParentId(courant.getId()).forEach(aTraiter::push);
        }
        return resultat;
    }

    private PhysicalLocationNodeDto versNode(PhysicalLocation n, Map<UUID, List<PhysicalLocation>> parEnfantsDe)
    {
        List<PhysicalLocation> enfants = parEnfantsDe.getOrDefault(n.getId(), List.of());
        return PhysicalLocationNodeDto.builder()
            .id(n.getId())
            .name(n.getName())
            .status(n.getStatus().name())
            .storagePoint(n.isStoragePoint())
            .children(enfants.stream().map(e -> versNode(e, parEnfantsDe)).toList())
            .build();
    }

    private PhysicalLocationDto toDto(PhysicalLocation loc)
    {
        return PhysicalLocationDto.builder()
            .id(loc.getId())
            .name(loc.getName())
            .description(loc.getDescription())
            .status(loc.getStatus().name())
            .storagePoint(loc.isStoragePoint())
            .parentId(loc.getParent() != null ? loc.getParent().getId() : null)
            .uniteOrganisationnelleId(loc.getUniteOrganisationnelle().getId())
            .cheminComplet(construireChemin(loc))
            .createdAt(loc.getCreatedAt())
            .createdByNom(loc.getCreatedBy() != null
                ? loc.getCreatedBy().getPrenom() + " " + loc.getCreatedBy().getNom() : null)
            .updatedAt(loc.getUpdatedAt())
            .updatedByNom(loc.getUpdatedBy() != null
                ? loc.getUpdatedBy().getPrenom() + " " + loc.getUpdatedBy().getNom() : null)
            .build();
    }
}
