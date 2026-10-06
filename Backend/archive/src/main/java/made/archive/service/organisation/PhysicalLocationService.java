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
import made.archive.dto.PhysicalLocationArborescenceRequestDto;
import made.archive.dto.PhysicalLocationCreateDto;
import made.archive.dto.PhysicalLocationDto;
import made.archive.dto.PhysicalLocationNodeDto;
import made.archive.dto.PhysicalLocationTreeNodeDto;
import made.archive.dto.PhysicalLocationUpdateDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.Dossier;
import made.archive.entite.LocationModeContrainte;
import made.archive.entite.LocationStatus;
import made.archive.entite.PhysicalLocation;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.DossierRepository;
import made.archive.repository.PhysicalLocationRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.util.NormalisationNoms;

/**
 * Localisation physique des originaux papier — voir PhysicalLocation.
 *
 * Arbre entièrement libre par UO (pas de LocationType en enum, chaque UO
 * construit sa propre arborescence).
 *
 * ENTIÈREMENT piloté par l'éditeur (revu le 09/2026 — géré par ADMIN/ADMIN_UO
 * à l'origine, puis seule la création leur avait été retirée dans un premier
 * temps, avant ce passage complet) : créer/modifier/déplacer/convertir/
 * désactiver/réactiver/supprimer, TOUJOURS dans sa PROPRE UO uniquement (voir
 * estEditeurDeUO — même condition que DossierService.estEditeurDeUO). Même
 * modèle que les Dossiers, entièrement pilotés par l'éditeur.
 *
 * ADMIN et ADMIN_UO n'ont plus AUCUN droit d'écriture ici — uniquement un
 * droit de LECTURE (voir PhysicalLocationLectureController), comme pour les
 * Dossiers : l'éditeur est celui qui alimente concrètement l'arborescence
 * physique au quotidien, l'administration n'a pas à en défaire ce qu'il met
 * en place.
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
    private final TypeDocumentRepository typeDocumentRepository;
    private final DossierRepository dossierRepository;

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

        // EXCLUSIVEMENT l'éditeur de cette UO (retiré le 09/2026 pour ADMIN/ADMIN_UO,
        // qui gardent les autres actions — voir Javadoc de classe et de
        // PhysicalLocationController). Pas d'appel à aAutoriteSur ici : même un
        // ADMIN global ne doit plus passer cette porte, contrairement aux autres
        // méthodes de cette classe (modifier/deplacer/desactiver/...).
        if (!estEditeurDeUO(dto.getUniteOrganisationnelleId(), currentUser))
        {
            throw new AccessDeniedException(
                "Seul un éditeur de cette unité organisationnelle peut y créer un emplacement");
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

        verifierNomUnique(dto.getName(), uo.getId(), parent, null);

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
     * Crée UN emplacement et sa descendance en un seul appel — le client
     * construit tout le brouillon localement (une racine + ses enfants
     * imbriqués, à toute profondeur) puis l'envoie d'un coup ; chaque nœud
     * est créé ici en réutilisant EXACTEMENT les mêmes règles que creer()
     * (nom obligatoire, storagePoint sans enfant, unicité du nom entre
     * frères/sœurs), dans l'ordre d'un parcours en profondeur, le tout dans
     * UNE SEULE transaction : la moindre violation sur n'importe quel nœud
     * fait tout annuler, jamais d'arborescence à moitié créée.
     *
     * TOUJOURS une seule racine par appel (jamais plusieurs racines
     * indépendantes) — pour plusieurs emplacements racine, on répète
     * l'action bouton par bouton, une racine + sa descendance à la fois.
     *
     * parentId ne désigne QUE le point d'accroche de la racine envoyée (null
     * = racine de l'UO) — mêmes vérifications que le parent de creer() :
     * doit être un nœud chemin actif de la même UO.
     *
     * Un seul log d'audit récapitulatif est émis pour tout l'appel (pas un
     * par nœud créé), pour ne pas noyer le journal sous des dizaines
     * d'entrées identiques lors d'une construction en masse.
     */
    @Transactional
    public PhysicalLocationNodeDto creerArborescence(PhysicalLocationArborescenceRequestDto dto, User currentUser)
    {
        if (dto.getUniteOrganisationnelleId() == null)
        {
            throw new BusinessException("L'unité organisationnelle est obligatoire");
        }
        if (dto.getNode() == null)
        {
            throw new BusinessException("La racine de l'arborescence est obligatoire");
        }
        if (!estEditeurDeUO(dto.getUniteOrganisationnelleId(), currentUser))
        {
            throw new AccessDeniedException(
                "Seul un éditeur de cette unité organisationnelle peut y créer un emplacement");
        }

        // La racine d'une arborescence SANS parent (créée directement à la
        // racine de l'UO) ne peut jamais être un point de stockage — retour
        // utilisateur 10/2026 : un emplacement physique part forcément d'un
        // conteneur organisationnel (bâtiment, salle...), jamais directement
        // d'une boîte isolée au sommet de l'UO. Déjà imposé côté client (voir
        // EmplacementTreeModal, racineNouvelleVerrouillee) — revalidé ici
        // pour ne pas dépendre uniquement du frontend.
        if (dto.getParentId() == null && dto.getNode().isStoragePoint())
        {
            throw new BusinessException(
                "La racine d'un nouvel emplacement ne peut pas être un point de stockage — "
                    + "ce doit être un nœud chemin (conteneur)");
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
                    "L'emplacement doit appartenir à la même UO que son parent");
            }
        }

        int[] compteur = { 0 };
        PhysicalLocationNodeDto racine = creerNoeudArborescence(dto.getNode(), uo, parent, currentUser, compteur);

        auditLogService.log(currentUser, AuditAction.LOCATION_CREEE, AuditCible.PHYSICAL_LOCATION,
            racine.getId().toString(), uo.getId(),
            "Création de \"" + racine.getName() + "\""
                + (compteur[0] > 1 ? " et de " + (compteur[0] - 1) + " descendant(s)" : "")
                + (parent != null ? " sous \"" + parent.getName() + "\"" : " (racine)"),
            true);

        return racine;
    }

    /** Crée récursivement un nœud puis ses enfants — voir creerArborescence. */
    private PhysicalLocationNodeDto creerNoeudArborescence(PhysicalLocationTreeNodeDto node, UniteOrganisationnelle uo,
                                                             PhysicalLocation parent, User currentUser, int[] compteur)
    {
        if (node.getName() == null || node.getName().isBlank())
        {
            throw new BusinessException("Le nom est obligatoire pour chaque nœud de l'arborescence");
        }

        boolean aDesEnfants = node.getChildren() != null && !node.getChildren().isEmpty();
        if (node.isStoragePoint() && aDesEnfants)
        {
            throw new BusinessException(
                "\"" + node.getName() + "\" est un point de stockage, il ne peut pas avoir d'enfant");
        }

        // Un nœud chemin n'a pas de capacité : une valeur résiduelle (un point de stockage dont on a donné la capacité
        // avant de le repasser en chemin) est simplement ignorée, jamais une erreur.
        Integer capaciteMax = node.isStoragePoint() ? node.getCapaciteMax() : null;
        if (capaciteMax != null && capaciteMax < 1)
        {
            throw new BusinessException("La capacité maximale doit être d'au moins 1 document");
        }

        verifierNomUnique(node.getName(), uo.getId(), parent, null);

        PhysicalLocation loc = new PhysicalLocation();
        loc.setName(node.getName());
        loc.setDescription(node.getDescription());
        loc.setStoragePoint(node.isStoragePoint());
        loc.setCapaciteMax(capaciteMax);
        loc.setParent(parent);
        loc.setUniteOrganisationnelle(uo);
        loc.setStatus(LocationStatus.ACTIVE);
        loc.setCreatedBy(currentUser);
        loc.setCreatedAt(LocalDateTime.now());

        if (node.isStoragePoint())
        {
            appliquerContrainte(loc, node.getModeContrainte(), node.getTypeDocumentId(), node.getDossierId(), uo);
        }

        PhysicalLocation saved = locationRepository.save(loc);
        compteur[0]++;

        List<PhysicalLocationNodeDto> enfants = new ArrayList<>();
        if (aDesEnfants)
        {
            for (PhysicalLocationTreeNodeDto enfant : node.getChildren())
            {
                enfants.add(creerNoeudArborescence(enfant, uo, saved, currentUser, compteur));
            }
        }

        return PhysicalLocationNodeDto.builder()
            .id(saved.getId())
            .name(saved.getName())
            .status(saved.getStatus().name())
            .storagePoint(saved.isStoragePoint())
            .capaciteMax(saved.getCapaciteMax())
            .nombreDocuments(0L)
            .modeContrainte(saved.getModeContrainte().name())
            .typeDocumentAccepteId(saved.getTypeDocumentAccepte() != null ? saved.getTypeDocumentAccepte().getId() : null)
            .typeDocumentAccepteNom(saved.getTypeDocumentAccepte() != null ? saved.getTypeDocumentAccepte().getNom() : null)
            .dossierId(saved.getDossier() != null ? saved.getDossier().getId() : null)
            .dossierNom(saved.getDossier() != null ? saved.getDossier().getNom() : null)
            .children(enfants)
            .build();
    }

    /**
     * Modifie UN emplacement existant (nom) et sa descendance en un seul
     * appel — même expérience que creerArborescence, pour un nœud déjà en
     * base : le client pré-remplit le brouillon avec l'arborescence RÉELLE
     * actuelle (id inclus sur chaque nœud existant), l'utilisateur peut
     * renommer n'importe quel nœud existant ET ajouter de nouveaux
     * descendants n'importe où, puis tout est envoyé d'un coup.
     *
     * La SUPPRESSION d'un descendant existant n'est PAS gérée ici, par
     * design — un nœud absent du brouillon envoyé n'est JAMAIS supprimé,
     * seulement ignoré (reste tel quel en base). Supprimer reste
     * exclusivement le fait de supprimer(), avec sa confirmation dédiée :
     * silencieusement interpréter une absence comme une suppression serait
     * dangereux si le nœud contient des documents.
     *
     * dto.getId() est ignoré à la racine (le nœud modifié est déjà désigné
     * par rootId) — chaque ENFANT de dto avec un id renomme le nœud existant
     * correspondant (en vérifiant qu'il est bien un descendant DIRECT du
     * nœud sous lequel il est placé dans le brouillon — jamais un id
     * arbitraire d'ailleurs dans l'arbre), chaque enfant SANS id crée un
     * nouveau nœud (mêmes règles que creerNoeudArborescence).
     *
     * La description n'est JAMAIS touchée ici, ni pour la racine ni pour les
     * descendants — ce constructeur d'arborescence ne l'expose plus du tout
     * (jugée superflue/encombrante, retirée côté client), donc dto ne la
     * porte jamais : la conserver telle quelle en base est le seul
     * comportement sûr (la modifier() dédiée reste le moyen d'éditer une
     * description au besoin).
     */
    @Transactional
    public PhysicalLocationNodeDto mettreAJourArborescence(UUID rootId, PhysicalLocationTreeNodeDto dto, User currentUser)
    {
        PhysicalLocation root = getEtVerifierAutorite(rootId, currentUser);

        if (dto.getName() == null || dto.getName().isBlank())
        {
            throw new BusinessException("Le nom est obligatoire");
        }

        verifierNomUnique(dto.getName(), root.getUniteOrganisationnelle().getId(), root.getParent(), root.getId());
        root.setName(dto.getName());
        root.setUpdatedBy(currentUser);
        root.setUpdatedAt(LocalDateTime.now());
        locationRepository.save(root);

        int[] compteurNouveaux = { 0 };
        int[] compteurRenommes = { 0 };
        List<PhysicalLocationNodeDto> enfants = new ArrayList<>();
        if (dto.getChildren() != null)
        {
            for (PhysicalLocationTreeNodeDto enfant : dto.getChildren())
            {
                enfants.add(appliquerNoeudMiseAJour(enfant, root.getUniteOrganisationnelle(), root, currentUser,
                    compteurNouveaux, compteurRenommes));
            }
        }

        auditLogService.log(currentUser, AuditAction.LOCATION_MODIFIEE, AuditCible.PHYSICAL_LOCATION,
            root.getId().toString(), root.getUniteOrganisationnelle().getId(),
            "Mise à jour de \"" + root.getName() + "\""
                + (compteurRenommes[0] > 0 ? " — " + compteurRenommes[0] + " descendant(s) renommé(s)" : "")
                + (compteurNouveaux[0] > 0 ? ", " + compteurNouveaux[0] + " nouveau(x) descendant(s)" : ""),
            true);

        return versNodeDtoSimple(root, enfants);
    }

    /** Applique récursivement un nœud de brouillon (renommage si existant, création si nouveau) — voir mettreAJourArborescence. */
    private PhysicalLocationNodeDto appliquerNoeudMiseAJour(PhysicalLocationTreeNodeDto dto, UniteOrganisationnelle uo,
                                                              PhysicalLocation parentAttendu, User currentUser,
                                                              int[] compteurNouveaux, int[] compteurRenommes)
    {
        if (dto.getName() == null || dto.getName().isBlank())
        {
            throw new BusinessException("Le nom est obligatoire pour chaque nœud");
        }

        boolean aDesEnfants = dto.getChildren() != null && !dto.getChildren().isEmpty();
        PhysicalLocation loc;

        if (dto.getId() != null)
        {
            loc = locationRepository.findById(dto.getId())
                .orElseThrow(() -> new BusinessException("Emplacement introuvable : " + dto.getId()));
            if (loc.getParent() == null || !loc.getParent().getId().equals(parentAttendu.getId()))
            {
                throw new BusinessException(
                    "\"" + dto.getName() + "\" n'est pas un descendant direct attendu à cet endroit de l'arborescence");
            }
            if (loc.isStoragePoint() && aDesEnfants)
            {
                throw new BusinessException(
                    "\"" + loc.getName() + "\" est un point de stockage, il ne peut pas avoir d'enfant");
            }
            verifierNomUnique(dto.getName(), uo.getId(), parentAttendu, loc.getId());
            loc.setName(dto.getName());
            loc.setUpdatedBy(currentUser);
            loc.setUpdatedAt(LocalDateTime.now());
            loc = locationRepository.save(loc);
            compteurRenommes[0]++;
        }
        else
        {
            if (dto.isStoragePoint() && aDesEnfants)
            {
                throw new BusinessException(
                    "\"" + dto.getName() + "\" est un point de stockage, il ne peut pas avoir d'enfant");
            }
            // Capacité résiduelle d'un nœud devenu chemin : ignorée (voir creerNoeudArborescence).
            Integer capaciteMax = dto.isStoragePoint() ? dto.getCapaciteMax() : null;
            if (capaciteMax != null && capaciteMax < 1)
            {
                throw new BusinessException("La capacité maximale doit être d'au moins 1 document");
            }
            verifierNomUnique(dto.getName(), uo.getId(), parentAttendu, null);

            loc = new PhysicalLocation();
            loc.setName(dto.getName());
            loc.setDescription(dto.getDescription());
            loc.setStoragePoint(dto.isStoragePoint());
            loc.setCapaciteMax(capaciteMax);
            loc.setParent(parentAttendu);
            loc.setUniteOrganisationnelle(uo);
            loc.setStatus(LocationStatus.ACTIVE);
            loc.setCreatedBy(currentUser);
            loc.setCreatedAt(LocalDateTime.now());
            if (dto.isStoragePoint())
            {
                appliquerContrainte(loc, dto.getModeContrainte(), dto.getTypeDocumentId(), dto.getDossierId(), uo);
            }
            loc = locationRepository.save(loc);
            compteurNouveaux[0]++;
        }

        List<PhysicalLocationNodeDto> enfants = new ArrayList<>();
        if (aDesEnfants)
        {
            for (PhysicalLocationTreeNodeDto enfant : dto.getChildren())
            {
                enfants.add(appliquerNoeudMiseAJour(enfant, uo, loc, currentUser, compteurNouveaux, compteurRenommes));
            }
        }

        return versNodeDtoSimple(loc, enfants);
    }

    /** Fabrique un PhysicalLocationNodeDto pour UN nœud déjà persisté (nombreDocuments recalculé, enfants fournis) —
     *  factorisé entre mettreAJourArborescence/appliquerNoeudMiseAJour (versNode n'est pas réutilisable ici, il
     *  attend une Map d'enfants bruts pré-groupée, pas une liste déjà convertie). */
    private PhysicalLocationNodeDto versNodeDtoSimple(PhysicalLocation loc, List<PhysicalLocationNodeDto> enfants)
    {
        long nombreDocuments = loc.isStoragePoint()
            ? documentRepository.countByPhysicalLocationIdAndStatusNot(loc.getId(), DocumentStatus.DELETED)
            : 0L;
        return PhysicalLocationNodeDto.builder()
            .id(loc.getId())
            .name(loc.getName())
            .status(loc.getStatus().name())
            .storagePoint(loc.isStoragePoint())
            .capaciteMax(loc.getCapaciteMax())
            .nombreDocuments(nombreDocuments)
            .modeContrainte(loc.getModeContrainte().name())
            .typeDocumentAccepteId(loc.getTypeDocumentAccepte() != null ? loc.getTypeDocumentAccepte().getId() : null)
            .typeDocumentAccepteNom(loc.getTypeDocumentAccepte() != null ? loc.getTypeDocumentAccepte().getNom() : null)
            .dossierId(loc.getDossier() != null ? loc.getDossier().getId() : null)
            .dossierNom(loc.getDossier() != null ? loc.getDossier().getNom() : null)
            .children(enfants)
            .build();
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

        // Un déplacement peut faire atterrir loc parmi de nouveaux frères/sœurs —
        // même vérification qu'à la création, sur SA propre UO (le déplacement ne
        // change jamais d'UO, voir plus haut) et le NOUVEAU parent.
        verifierNomUnique(loc.getName(), loc.getUniteOrganisationnelle().getId(), nouveauParent, id);

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
            verifierNomUnique(dto.getName(), loc.getUniteOrganisationnelle().getId(), loc.getParent(), id);
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
        if (nouveauStoragePoint && loc.getParent() == null)
        {
            throw new BusinessException(
                "Impossible de devenir un point de stockage : une racine reste toujours un nœud chemin");
        }

        loc.setStoragePoint(nouveauStoragePoint);
        if (!nouveauStoragePoint)
        {
            // Un chemin n'a ni capacité ni contrainte d'acceptation : ce qui restait du point de stockage disparaît.
            loc.setCapaciteMax(null);
            loc.setModeContrainte(LocationModeContrainte.LIBRE);
            loc.setTypeDocumentAccepte(null);
            loc.setDossier(null);
        }
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
     * Fixe (ou retire, si capaciteMax == null) la limite de documents d'un
     * point de stockage — contrairement à modifier() (name/description),
     * endpoint DÉDIÉ car null est ici une valeur SIGNIFICATIVE ("aucune
     * limite"), pas "champ non fourni" : un DTO à plusieurs champs optionnels
     * ne peut pas distinguer les deux avec Jackson, cet appel à un seul
     * paramètre le peut. Modifiable à tout moment, PAS seulement si le nœud
     * est vide (augmenter ou retirer une limite ne casse jamais rien) — mais
     * refusé si la nouvelle valeur est inférieure au nombre de documents déjà
     * rattachés, ce qui laisserait un nœud immédiatement "en dépassement".
     */
    @Transactional
    public PhysicalLocationDto definirCapacite(UUID id, Integer capaciteMax, User currentUser)
    {
        PhysicalLocation loc = getEtVerifierAutorite(id, currentUser);

        if (!loc.isStoragePoint())
        {
            throw new BusinessException("Seul un point de stockage peut avoir une capacité maximale");
        }

        if (capaciteMax != null)
        {
            if (capaciteMax < 1)
            {
                throw new BusinessException("La capacité maximale doit être d'au moins 1 document");
            }
            long occupees = documentRepository.countByPhysicalLocationIdAndStatusNot(id, DocumentStatus.DELETED);
            if (capaciteMax < occupees)
            {
                throw new BusinessException("Impossible : " + occupees + " document(s) déjà rattaché(s) à \""
                    + loc.getName() + "\" — la capacité ne peut pas être fixée en dessous");
            }
        }

        loc.setCapaciteMax(capaciteMax);
        loc.setUpdatedBy(currentUser);
        loc.setUpdatedAt(LocalDateTime.now());
        PhysicalLocation saved = locationRepository.save(loc);

        auditLogService.log(currentUser, AuditAction.LOCATION_MODIFIEE, AuditCible.PHYSICAL_LOCATION,
            saved.getId().toString(), saved.getUniteOrganisationnelle().getId(),
            capaciteMax != null
                ? "Capacité maximale de \"" + saved.getName() + "\" fixée à " + capaciteMax + " document(s)"
                : "Capacité maximale de \"" + saved.getName() + "\" retirée (illimitée)",
            true);

        return toDto(saved);
    }

    /**
     * Fixe le mode de contrainte d'acceptation (LIBRE/TYPE_UNIQUE/DOSSIER) —
     * voir LocationModeContrainte. Contrairement à la capacité, modifiable
     * SEULEMENT si le nœud est vide (aucun document vivant rattaché) : changer
     * ce qu'un nœud accepte alors qu'il contient déjà des documents qui ne
     * respecteraient plus la nouvelle règle laisserait le nœud dans un état
     * incohérent — même principe que changerTypeStockage pour storagePoint.
     */
    @Transactional
    public PhysicalLocationDto definirContrainte(
        UUID id, String modeContrainte, Long typeDocumentId, Long dossierId, User currentUser)
    {
        PhysicalLocation loc = getEtVerifierAutorite(id, currentUser);

        if (!loc.isStoragePoint())
        {
            throw new BusinessException("Seul un point de stockage peut avoir une contrainte d'acceptation");
        }
        if (documentRepository.existsByPhysicalLocationIdAndStatusNot(id, DocumentStatus.DELETED))
        {
            throw new BusinessException(
                "Impossible de changer la contrainte : des documents sont déjà rattachés à cet emplacement");
        }

        appliquerContrainte(loc, modeContrainte, typeDocumentId, dossierId, loc.getUniteOrganisationnelle());
        loc.setUpdatedBy(currentUser);
        loc.setUpdatedAt(LocalDateTime.now());
        PhysicalLocation saved = locationRepository.save(loc);

        String description = switch (saved.getModeContrainte())
        {
            case TYPE_UNIQUE -> "type unique (\"" + saved.getTypeDocumentAccepte().getNom() + "\")";
            case DOSSIER -> "dossier unique (\"" + saved.getDossier().getNom() + "\")";
            default -> "libre (aucune contrainte)";
        };
        auditLogService.log(currentUser, AuditAction.LOCATION_MODIFIEE, AuditCible.PHYSICAL_LOCATION,
            saved.getId().toString(), saved.getUniteOrganisationnelle().getId(),
            "Contrainte d'acceptation de \"" + saved.getName() + "\" fixée à : " + description, true);

        return toDto(saved);
    }

    /**
     * Résout et valide modeContrainte/typeDocumentId/dossierId pour un point
     * de stockage — factorisé entre creerNoeudArborescence (nouveau nœud) et
     * definirContrainte (nœud existant, déjà vérifié vide par l'appelant).
     * Ne sauvegarde jamais elle-même (locationRepository.save() reste à la
     * charge de l'appelant) — se contente de poser les champs sur loc.
     */
    private void appliquerContrainte(
        PhysicalLocation loc, String modeContrainteStr, Long typeDocumentId, Long dossierId, UniteOrganisationnelle uo)
    {
        LocationModeContrainte mode;
        try
        {
            mode = (modeContrainteStr == null || modeContrainteStr.isBlank())
                ? LocationModeContrainte.LIBRE : LocationModeContrainte.valueOf(modeContrainteStr);
        }
        catch (IllegalArgumentException e)
        {
            throw new BusinessException("Mode de contrainte invalide : " + modeContrainteStr);
        }

        loc.setModeContrainte(mode);
        loc.setTypeDocumentAccepte(null);
        loc.setDossier(null);

        if (mode == LocationModeContrainte.TYPE_UNIQUE)
        {
            if (typeDocumentId == null)
            {
                throw new BusinessException("Le type de document accepté est obligatoire pour ce mode");
            }
            TypeDocument type = typeDocumentRepository.findById(typeDocumentId)
                .orElseThrow(() -> new BusinessException("Type de document introuvable : " + typeDocumentId));
            if (!type.getUniteOrganisationnelle().getId().equals(uo.getId()))
            {
                throw new BusinessException("Ce type de document n'appartient pas à la même unité organisationnelle");
            }
            loc.setTypeDocumentAccepte(type);
        }
        else if (mode == LocationModeContrainte.DOSSIER)
        {
            if (dossierId == null)
            {
                throw new BusinessException("Le dossier accepté est obligatoire pour ce mode");
            }
            Dossier dossier = dossierRepository.findById(dossierId)
                .orElseThrow(() -> new BusinessException("Dossier introuvable : " + dossierId));
            if (!dossier.getUniteOrganisationnelle().getId().equals(uo.getId()))
            {
                throw new BusinessException("Ce dossier n'appartient pas à la même unité organisationnelle");
            }
            loc.setDossier(dossier);
        }
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

    /** Arbre complet (tous statuts) d'une UO — reconstruit en mémoire à partir d'un seul SELECT,
     *  taux d'occupation inclus (un second SELECT groupé, jamais un par nœud — voir
     *  DocumentRepository.countDocumentsGroupedByPhysicalLocation). */
    @Transactional(readOnly = true)
    public List<PhysicalLocationNodeDto> getArbre(Long uoId, User currentUser)
    {
        verifierVisiblePourLecture(uoId, currentUser);

        List<PhysicalLocation> tous = locationRepository.findByUniteOrganisationnelleId(uoId);
        Map<UUID, List<PhysicalLocation>> parEnfantsDe = tous.stream()
            .filter(n -> n.getParent() != null)
            .collect(Collectors.groupingBy(n -> n.getParent().getId()));

        List<UUID> pointsDeStockage = tous.stream()
            .filter(PhysicalLocation::isStoragePoint)
            .map(PhysicalLocation::getId)
            .toList();
        Map<UUID, Long> comptesParEmplacement = pointsDeStockage.isEmpty() ? Map.of()
            : documentRepository.countDocumentsGroupedByPhysicalLocation(pointsDeStockage, DocumentStatus.DELETED)
                .stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));

        return tous.stream()
            .filter(n -> n.getParent() == null)
            .map(n -> versNode(n, parEnfantsDe, comptesParEmplacement))
            .toList();
    }

    /**
     * Emplacements assignables à un document (storagePoint=true, ACTIVE) pour
     * une UO — filtré par compatibilité si typeDocumentId et/ou dossierId
     * sont fournis (voir LocationModeContrainte) : un nœud LIBRE est toujours
     * inclus, un nœud TYPE_UNIQUE seulement si typeDocumentId correspond, un
     * nœud DOSSIER seulement si dossierId correspond. Les deux null = aucun
     * filtrage de compatibilité (comportement historique, tous les points de
     * stockage actifs). PLUSIEURS nœuds peuvent revenir pour le même dossier
     * (voir LocationModeContrainte.DOSSIER) — le choix entre eux reste
     * entièrement manuel côté client, chacun annoté de son taux d'occupation
     * (nombreDocuments/capaciteMax) pour permettre une décision éclairée.
     */
    @Transactional(readOnly = true)
    public List<PhysicalLocationDto> getEmplacementsDisponibles(
        Long uoId, Long typeDocumentId, Long dossierId, User currentUser)
    {
        verifierVisiblePourLecture(uoId, currentUser);
        return locationRepository
            .findByUniteOrganisationnelleIdAndStoragePointTrueAndStatus(uoId, LocationStatus.ACTIVE)
            .stream()
            .filter(loc -> estCompatible(loc, typeDocumentId, dossierId))
            .map(this::toDto)
            .toList();
    }

    /** true si aucun filtre n'est demandé, ou si le nœud accepterait un document portant ces critères. */
    private boolean estCompatible(PhysicalLocation loc, Long typeDocumentId, Long dossierId)
    {
        if (typeDocumentId == null && dossierId == null)
        {
            return true;
        }
        return switch (loc.getModeContrainte())
        {
            case LIBRE -> true;
            case TYPE_UNIQUE -> typeDocumentId != null && loc.getTypeDocumentAccepte().getId().equals(typeDocumentId);
            case DOSSIER -> dossierId != null && loc.getDossier().getId().equals(dossierId);
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // Utilisé par DocumentService/DocumentUploadeService
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Résout et valide un emplacement pour le rattacher à un document : doit
     * être un point de stockage ACTIF de la MÊME UO que le document,
     * compatible avec sa contrainte d'acceptation (voir
     * LocationModeContrainte), et disposer encore de place (capaciteMax).
     * SEUL point de passage pour tout rattachement (archivage initial via
     * DocumentUploadeService, ou changement après coup via
     * DocumentService.modifierEmplacementPhysique) — toute règle posée ici
     * s'applique donc automatiquement aux deux.
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

        if (loc.getModeContrainte() == LocationModeContrainte.TYPE_UNIQUE)
        {
            if (document.getTypeDocument() == null
                || !loc.getTypeDocumentAccepte().getId().equals(document.getTypeDocument().getId()))
            {
                throw new BusinessException("\"" + loc.getName() + "\" n'accepte que les documents de type \""
                    + loc.getTypeDocumentAccepte().getNom() + "\"");
            }
        }
        else if (loc.getModeContrainte() == LocationModeContrainte.DOSSIER)
        {
            if (document.getDossier() == null || !loc.getDossier().getId().equals(document.getDossier().getId()))
            {
                throw new BusinessException("\"" + loc.getName() + "\" n'accepte que les documents du dossier \""
                    + loc.getDossier().getNom() + "\"");
            }
        }

        if (loc.getCapaciteMax() != null)
        {
            // Un document déjà sur CE nœud (ex. reconfirmation d'un emplacement
            // inchangé) ne doit jamais se compter lui-même comme "en plus".
            boolean dejaSurCeNoeud = document.getPhysicalLocation() != null
                && document.getPhysicalLocation().getId().equals(loc.getId());
            if (!dejaSurCeNoeud)
            {
                long occupees = documentRepository.countByPhysicalLocationIdAndStatusNot(loc.getId(), DocumentStatus.DELETED);
                if (occupees >= loc.getCapaciteMax())
                {
                    throw new BusinessException("\"" + loc.getName() + "\" a atteint sa capacité maximale ("
                        + loc.getCapaciteMax() + " document(s)) — choisissez un autre emplacement");
                }
            }
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

    /**
     * Toutes les actions d'écriture (modifier/déplacer/convertir/désactiver/
     * réactiver/supprimer) sont désormais EXCLUSIVEMENT réservées à l'éditeur
     * de l'UO de cet emplacement (retiré à ADMIN/ADMIN_UO le 09/2026 — ils
     * gardent un droit de LECTURE seulement, voir PhysicalLocationLectureController)
     * — même modèle que les Dossiers, entièrement pilotés par l'éditeur.
     */
    private PhysicalLocation getEtVerifierAutorite(UUID id, User currentUser)
    {
        PhysicalLocation loc = locationRepository.findById(id)
            .orElseThrow(() -> new BusinessException("Emplacement introuvable : " + id));

        if (!estEditeurDeUO(loc.getUniteOrganisationnelle().getId(), currentUser))
        {
            throw new AccessDeniedException("Seul un éditeur de cette unité organisationnelle peut gérer cet emplacement");
        }
        return loc;
    }

    /**
     * Deux vérifications de nom, après NORMALISATION (casse, accents,
     * espacement intégralement retiré — voir NormalisationNoms) :
     *
     *   1. Doublon entre FRÈRES/SŒURS — même parent (ou même niveau racine
     *      si parent==null), DANS LA MÊME UO. NOUVEAU (aucune vérification
     *      n'existait avant le 09/2026 pour les emplacements physiques :
     *      deux emplacements strictement identiques pouvaient déjà
     *      coexister) — même logique que
     *      UniteOrganisationnelleService.verifierNomUnique.
     *   2. Même nom que le PARENT DIRECT — interdit, même normalisé
     *      identique (ex. "Salle" ne peut pas avoir un enfant "Salle") ;
     *      un nœud RACINE (parent==null) n'a rien à comparer, jamais
     *      concerné par cette règle.
     *
     * Appelée à la création, à la modification (renommage), au déplacement
     * (un nœud déplacé peut atterrir parmi de nouveaux frères/sœurs ET sous
     * un nouveau parent) et dans les deux flux d'arborescence. exclutId :
     * l'emplacement qu'on est en train de renommer/déplacer (null à la
     * création) — jamais nécessaire pour la comparaison au parent, un nœud
     * n'est jamais son propre parent.
     */
    private void verifierNomUnique(String nom, Long uoId, PhysicalLocation parent, UUID exclutId)
    {
        List<PhysicalLocation> fratrie = (parent == null)
            ? locationRepository.findByParentIsNullAndUniteOrganisationnelleId(uoId)
            : locationRepository.findByParentId(parent.getId());

        String nomNormalise = NormalisationNoms.normaliser(nom);
        boolean conflit = fratrie.stream()
            .anyMatch(l -> NormalisationNoms.normaliser(l.getName()).equals(nomNormalise)
                && (exclutId == null || !l.getId().equals(exclutId)));

        if (conflit)
        {
            throw new BusinessException("Un emplacement avec ce nom existe déjà à cet endroit");
        }

        if (parent != null && NormalisationNoms.normaliser(parent.getName()).equals(nomNormalise))
        {
            throw new BusinessException(
                "Un emplacement ne peut pas porter le même nom que son parent direct (\"" + parent.getName() + "\")");
        }
    }

    /**
     * true si l'acteur est EDITOR et que cette UO est bien SA propre UO
     * actuelle — jamais une UO descendante ou une autre branche (contrairement
     * à aAutoriteSur pour ADMIN_UO) : un éditeur ne gère que son propre
     * terrain. Même logique que DossierService.estEditeurDeUO.
     */
    private boolean estEditeurDeUO(Long uoId, User acteur)
    {
        boolean estEditeur = acteur.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.EDITOR);
        if (!estEditeur)
        {
            return false;
        }
        return uoService.getUOActuelleUser(acteur.getId())
            .map(dto -> uoId.equals(dto.getId()))
            .orElse(false);
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
     * voyant normalement cette UO (même règle que documents/dossiers, voir
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

    private PhysicalLocationNodeDto versNode(
        PhysicalLocation n, Map<UUID, List<PhysicalLocation>> parEnfantsDe, Map<UUID, Long> comptesParEmplacement)
    {
        List<PhysicalLocation> enfants = parEnfantsDe.getOrDefault(n.getId(), List.of());
        return PhysicalLocationNodeDto.builder()
            .id(n.getId())
            .name(n.getName())
            .status(n.getStatus().name())
            .storagePoint(n.isStoragePoint())
            .capaciteMax(n.getCapaciteMax())
            .nombreDocuments(comptesParEmplacement.getOrDefault(n.getId(), 0L))
            .modeContrainte(n.getModeContrainte().name())
            .typeDocumentAccepteId(n.getTypeDocumentAccepte() != null ? n.getTypeDocumentAccepte().getId() : null)
            .typeDocumentAccepteNom(n.getTypeDocumentAccepte() != null ? n.getTypeDocumentAccepte().getNom() : null)
            .dossierId(n.getDossier() != null ? n.getDossier().getId() : null)
            .dossierNom(n.getDossier() != null ? n.getDossier().getNom() : null)
            .children(enfants.stream().map(e -> versNode(e, parEnfantsDe, comptesParEmplacement)).toList())
            .build();
    }

    private PhysicalLocationDto toDto(PhysicalLocation loc)
    {
        long nombreDocuments = loc.isStoragePoint()
            ? documentRepository.countByPhysicalLocationIdAndStatusNot(loc.getId(), DocumentStatus.DELETED)
            : 0L;
        return PhysicalLocationDto.builder()
            .id(loc.getId())
            .name(loc.getName())
            .description(loc.getDescription())
            .status(loc.getStatus().name())
            .storagePoint(loc.isStoragePoint())
            .capaciteMax(loc.getCapaciteMax())
            .nombreDocuments(nombreDocuments)
            .modeContrainte(loc.getModeContrainte().name())
            .typeDocumentAccepteId(loc.getTypeDocumentAccepte() != null ? loc.getTypeDocumentAccepte().getId() : null)
            .typeDocumentAccepteNom(loc.getTypeDocumentAccepte() != null ? loc.getTypeDocumentAccepte().getNom() : null)
            .dossierId(loc.getDossier() != null ? loc.getDossier().getId() : null)
            .dossierNom(loc.getDossier() != null ? loc.getDossier().getNom() : null)
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
