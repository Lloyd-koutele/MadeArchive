package made.archive.service.organisation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import made.archive.dto.PlanClassementNoeudDto;
import made.archive.dto.PlanClassementNoeudRequestDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.PlanClassementNoeud;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.repository.PlanClassementNoeudRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.service.audit.AuditLogService;

/**
 * Plan de classement par UO — arborescence d'activités (code + libellé) à
 * laquelle on rattache des TYPES de documents (jamais des documents un par un :
 * un document hérite de l'activité de son type). Géré par l'ÉDITEUR de l'UO,
 * comme les types de documents, dossiers et emplacements physiques (ADMIN et
 * ADMIN_UO n'ont pas de droit d'écriture sur ces référentiels).
 *
 * Aucun effet sur les documents existants ni sur l'index Meilisearch : le
 * filtre par activité se calcule côté base (type -> nœud), pas dans l'index.
 */
@Service
@RequiredArgsConstructor
public class PlanClassementService
{
    private static final String SEPARATEUR_CHEMIN = " › ";

    private final PlanClassementNoeudRepository noeudRepository;
    private final TypeDocumentRepository        typeDocumentRepository;
    private final UniteOrganisationnelleService uniteOrganisationnelleService;
    private final AuditLogService               auditLogService;

    // ── Lecture ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PlanClassementNoeudDto> getArbre(Long uoId, User currentUser)
    {
        verifierEditeur(uoId, currentUser);

        List<PlanClassementNoeud> tous = noeudRepository.findByUniteOrganisationnelleId(uoId);
        Map<Long, Long> comptes = new HashMap<>();
        for (Object[] r : typeDocumentRepository.countParNoeudPourUo(uoId))
        {
            comptes.put((Long) r[0], (Long) r[1]);
        }
        Map<Long, List<PlanClassementNoeud>> enfants = tous.stream()
            .filter(n -> n.getParent() != null)
            .collect(Collectors.groupingBy(n -> n.getParent().getId()));

        return tous.stream()
            .filter(n -> n.getParent() == null)
            .sorted(parCode())
            .map(n -> versDto(n, enfants, comptes))
            .toList();
    }

    private PlanClassementNoeudDto versDto(PlanClassementNoeud n, Map<Long, List<PlanClassementNoeud>> enfants,
                                           Map<Long, Long> comptes)
    {
        List<PlanClassementNoeudDto> fils = enfants.getOrDefault(n.getId(), List.of()).stream()
            .sorted(parCode())
            .map(e -> versDto(e, enfants, comptes))
            .toList();
        return PlanClassementNoeudDto.builder()
            .id(n.getId())
            .code(n.getCode())
            .libelle(n.getLibelle())
            .parentId(n.getParent() != null ? n.getParent().getId() : null)
            .nbTypes(comptes.getOrDefault(n.getId(), 0L))
            .children(fils)
            .build();
    }

    private Comparator<PlanClassementNoeud> parCode()
    {
        return Comparator.comparing(PlanClassementNoeud::getCode, String.CASE_INSENSITIVE_ORDER);
    }

    /** "01 Enseignement › 01.2 Examens" — null si pas de nœud. Public : réutilisé pour afficher
     *  l'activité d'un type/document. À appeler dans une session ouverte (parents chargés paresseusement). */
    public static String chemin(PlanClassementNoeud noeud)
    {
        if (noeud == null) return null;
        List<String> segments = new ArrayList<>();
        Set<Long> vus = new HashSet<>();
        for (PlanClassementNoeud n = noeud; n != null && vus.add(n.getId()); n = n.getParent())
        {
            segments.add(0, n.getCode() + " " + n.getLibelle());
        }
        return String.join(SEPARATEUR_CHEMIN, segments);
    }

    /** Ce nœud + tous ses descendants — pour filtrer les documents "de cette activité ou en dessous". */
    @Transactional(readOnly = true)
    public Set<Long> idsAvecDescendants(Long noeudId)
    {
        PlanClassementNoeud racine = noeudRepository.findById(noeudId).orElse(null);
        if (racine == null) return Set.of();

        List<PlanClassementNoeud> tous = noeudRepository.findByUniteOrganisationnelleId(
            racine.getUniteOrganisationnelle().getId());
        Map<Long, List<Long>> enfants = new HashMap<>();
        for (PlanClassementNoeud n : tous)
        {
            if (n.getParent() != null)
            {
                enfants.computeIfAbsent(n.getParent().getId(), k -> new ArrayList<>()).add(n.getId());
            }
        }
        Set<Long> res = new HashSet<>();
        List<Long> pile = new ArrayList<>(List.of(noeudId));
        while (!pile.isEmpty())
        {
            Long id = pile.remove(pile.size() - 1);
            if (res.add(id))
            {
                pile.addAll(enfants.getOrDefault(id, List.of()));
            }
        }
        return res;
    }

    // ── Écriture (éditeur de l'UO) ───────────────────────────────────────

    @Transactional
    public PlanClassementNoeudDto creer(PlanClassementNoeudRequestDto dto, User currentUser)
    {
        if (dto.getUoId() == null)
        {
            throw new BusinessException("L'unité organisationnelle est obligatoire");
        }
        UniteOrganisationnelle uo = uniteOrganisationnelleService.getUOEntiteSiEditeur(dto.getUoId(), currentUser);

        String code = nettoyer(dto.getCode(), "Le code");
        String libelle = nettoyer(dto.getLibelle(), "Le libellé");
        verifierCodeUnique(uo.getId(), code, null);

        PlanClassementNoeud parent = null;
        if (dto.getParentId() != null)
        {
            parent = chargerDansUo(dto.getParentId(), uo.getId());
        }

        PlanClassementNoeud noeud = new PlanClassementNoeud();
        noeud.setCode(code);
        noeud.setLibelle(libelle);
        noeud.setParent(parent);
        noeud.setUniteOrganisationnelle(uo);
        noeudRepository.save(noeud);

        auditLogService.log(currentUser, AuditAction.PLAN_CLASSEMENT_NOEUD_CREE, AuditCible.PLAN_CLASSEMENT,
            noeud.getId().toString(), uo.getId(),
            "Activité \"" + code + " " + libelle + "\" ajoutée au plan de classement", true);

        return simple(noeud);
    }

    @Transactional
    public PlanClassementNoeudDto modifier(Long id, PlanClassementNoeudRequestDto dto, User currentUser)
    {
        PlanClassementNoeud noeud = charger(id);
        Long uoId = noeud.getUniteOrganisationnelle().getId();
        verifierEditeur(uoId, currentUser);

        String code = nettoyer(dto.getCode(), "Le code");
        String libelle = nettoyer(dto.getLibelle(), "Le libellé");
        verifierCodeUnique(uoId, code, id);

        String avant = noeud.getCode() + " " + noeud.getLibelle();
        noeud.setCode(code);
        noeud.setLibelle(libelle);
        noeudRepository.save(noeud);

        auditLogService.log(currentUser, AuditAction.PLAN_CLASSEMENT_NOEUD_MODIFIE, AuditCible.PLAN_CLASSEMENT,
            id.toString(), uoId,
            "Activité \"" + avant + "\" renommée en \"" + code + " " + libelle + "\"", true);

        return simple(noeud);
    }

    /** newParentId null = déplacer à la racine du plan. */
    @Transactional
    public PlanClassementNoeudDto deplacer(Long id, Long newParentId, User currentUser)
    {
        PlanClassementNoeud noeud = charger(id);
        Long uoId = noeud.getUniteOrganisationnelle().getId();
        verifierEditeur(uoId, currentUser);

        PlanClassementNoeud nouveauParent = null;
        if (newParentId != null)
        {
            nouveauParent = chargerDansUo(newParentId, uoId);
            if (idsAvecDescendants(id).contains(newParentId))
            {
                throw new BusinessException("Impossible de déplacer une activité sous elle-même ou sous l'une de ses sous-activités");
            }
        }

        noeud.setParent(nouveauParent);
        noeudRepository.save(noeud);

        auditLogService.log(currentUser, AuditAction.PLAN_CLASSEMENT_NOEUD_DEPLACE, AuditCible.PLAN_CLASSEMENT,
            id.toString(), uoId,
            "Activité \"" + noeud.getCode() + " " + noeud.getLibelle() + "\" déplacée "
                + (nouveauParent != null ? "sous \"" + nouveauParent.getCode() + " " + nouveauParent.getLibelle() + "\"" : "à la racine"),
            true);

        return simple(noeud);
    }

    @Transactional
    public void supprimer(Long id, User currentUser)
    {
        PlanClassementNoeud noeud = charger(id);
        Long uoId = noeud.getUniteOrganisationnelle().getId();
        verifierEditeur(uoId, currentUser);

        if (noeudRepository.existsByParentId(id))
        {
            throw new BusinessException("Cette activité contient des sous-activités — supprimez-les ou déplacez-les d'abord");
        }
        if (typeDocumentRepository.existsByPlanClassementNoeudId(id))
        {
            throw new BusinessException("Des types de documents sont encore rattachés à cette activité — détachez-les d'abord");
        }

        String libelle = noeud.getCode() + " " + noeud.getLibelle();
        noeudRepository.delete(noeud);

        auditLogService.log(currentUser, AuditAction.PLAN_CLASSEMENT_NOEUD_SUPPRIME, AuditCible.PLAN_CLASSEMENT,
            id.toString(), uoId, "Activité \"" + libelle + "\" supprimée du plan de classement", true);
    }

    /**
     * Rattache (ou détache, noeudId null) un type de document à une activité de SA UO.
     * Volontairement PAS soumis au verrou hasLinkedDocuments de TypeDocumentService.updateTypeDocument :
     * l'activité décrit le type, elle ne change rien aux documents déjà archivés (qui en héritent).
     */
    @Transactional
    public PlanClassementNoeudDto rattacherType(Long typeId, Long noeudId, User currentUser)
    {
        TypeDocument type = typeDocumentRepository.findById(typeId)
            .orElseThrow(() -> new BusinessException("Type de document introuvable : " + typeId));
        Long uoId = type.getUniteOrganisationnelle().getId();
        verifierEditeur(uoId, currentUser);

        PlanClassementNoeud nouveau = noeudId != null ? chargerDansUo(noeudId, uoId) : null;
        String avant = chemin(type.getPlanClassementNoeud());

        type.setPlanClassementNoeud(nouveau);
        typeDocumentRepository.save(type);

        auditLogService.log(currentUser, AuditAction.TYPE_DOCUMENT_ACTIVITE_MODIFIEE, AuditCible.TYPE_DOCUMENT,
            typeId.toString(), uoId,
            "Activité du type \"" + type.getNom() + "\" : " + (avant != null ? avant : "non classé")
                + " → " + (nouveau != null ? chemin(nouveau) : "non classé"), true);

        return nouveau != null ? simple(nouveau) : null;
    }

    // ── Utilitaires ──────────────────────────────────────────────────────

    private PlanClassementNoeudDto simple(PlanClassementNoeud n)
    {
        return PlanClassementNoeudDto.builder()
            .id(n.getId()).code(n.getCode()).libelle(n.getLibelle())
            .parentId(n.getParent() != null ? n.getParent().getId() : null)
            .children(List.of())
            .build();
    }

    private void verifierEditeur(Long uoId, User currentUser)
    {
        if (!uniteOrganisationnelleService.estEditeurDeUO(uoId, currentUser))
        {
            throw new AccessDeniedException("Seul un éditeur de cette UO peut gérer son plan de classement");
        }
    }

    private PlanClassementNoeud charger(Long id)
    {
        return noeudRepository.findById(id)
            .orElseThrow(() -> new BusinessException("Activité introuvable : " + id));
    }

    private PlanClassementNoeud chargerDansUo(Long id, Long uoId)
    {
        PlanClassementNoeud n = charger(id);
        if (!n.getUniteOrganisationnelle().getId().equals(uoId))
        {
            throw new BusinessException("Cette activité appartient au plan de classement d'une autre UO");
        }
        return n;
    }

    private void verifierCodeUnique(Long uoId, String code, Long exclutId)
    {
        boolean existe = noeudRepository.findByUniteOrganisationnelleId(uoId).stream()
            .anyMatch(n -> n.getCode().equalsIgnoreCase(code) && (exclutId == null || !n.getId().equals(exclutId)));
        if (existe)
        {
            throw new BusinessException("Le code \"" + code + "\" est déjà utilisé dans le plan de classement de cette UO");
        }
    }

    private String nettoyer(String valeur, String nomChamp)
    {
        if (valeur == null || valeur.isBlank())
        {
            throw new BusinessException(nomChamp + " est obligatoire");
        }
        return valeur.trim();
    }
}
