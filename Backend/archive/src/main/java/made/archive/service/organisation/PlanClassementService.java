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
import made.archive.dto.PlanClassementArbreRequestDto;
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
import made.archive.repository.DocumentRepository;
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
    private final DocumentRepository            documentRepository;
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
        Map<Long, Long> documents = new HashMap<>();
        for (Object[] r : documentRepository.countDocumentsParActivitePourUo(uoId))
        {
            documents.put((Long) r[0], (Long) r[1]);
        }
        Map<Long, List<PlanClassementNoeud>> enfants = tous.stream()
            .filter(n -> n.getParent() != null)
            .collect(Collectors.groupingBy(n -> n.getParent().getId()));

        return tous.stream()
            .filter(n -> n.getParent() == null)
            .sorted(parCode())
            .map(n -> versDto(n, enfants, comptes, documents))
            .toList();
    }

    private PlanClassementNoeudDto versDto(PlanClassementNoeud n, Map<Long, List<PlanClassementNoeud>> enfants,
                                           Map<Long, Long> comptes, Map<Long, Long> documents)
    {
        List<PlanClassementNoeudDto> fils = enfants.getOrDefault(n.getId(), List.of()).stream()
            .sorted(parCode())
            .map(e -> versDto(e, enfants, comptes, documents))
            .toList();
        long directs = documents.getOrDefault(n.getId(), 0L);
        // Verrouillé dès qu'un document est classé ici OU dans une sous-activité (le code de celle-ci en dépend).
        boolean verrouille = directs > 0 || fils.stream().anyMatch(PlanClassementNoeudDto::isVerrouille);
        return PlanClassementNoeudDto.builder()
            .id(n.getId())
            .code(n.getCode())
            .libelle(n.getLibelle())
            .parentId(n.getParent() != null ? n.getParent().getId() : null)
            .nbTypes(comptes.getOrDefault(n.getId(), 0L))
            .nbDocuments(directs)
            .verrouille(verrouille)
            .children(fils)
            .build();
    }

    /** Tri par segments numériques (01.2 avant 01.10) ; un segment non numérique (ancien code libre) se compare en texte. */
    private Comparator<PlanClassementNoeud> parCode()
    {
        return (a, b) -> comparerCodes(a.getCode(), b.getCode());
    }

    static int comparerCodes(String a, String b)
    {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++)
        {
            boolean nx = x[i].matches("\\d{1,9}");
            boolean ny = y[i].matches("\\d{1,9}");
            int c = nx && ny
                ? Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]))
                : x[i].compareToIgnoreCase(y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
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

    /** "Enseignement › Examens" — comme {@link #chemin} mais SANS les codes, pour l'affichage (détail d'un document). */
    public static String cheminLibelles(PlanClassementNoeud noeud)
    {
        if (noeud == null) return null;
        List<String> segments = new ArrayList<>();
        Set<Long> vus = new HashSet<>();
        for (PlanClassementNoeud n = noeud; n != null && vus.add(n.getId()); n = n.getParent())
        {
            segments.add(0, n.getLibelle());
        }
        return String.join(SEPARATEUR_CHEMIN, segments);
    }

    /**
     * L'activité qui s'applique à CE document : celle qui lui est propre si elle a été précisée, sinon celle
     * de son type. Null = non classé. Seule règle de dérivation : l'affichage, le filtre de recherche, l'export
     * SEDA et les procès-verbaux passent tous par ici (ou par son équivalent JPQL COALESCE de l'export).
     */
    public static PlanClassementNoeud activiteEffective(made.archive.entite.Document document)
    {
        if (document.getPlanClassementNoeud() != null)
        {
            return document.getPlanClassementNoeud();
        }
        return document.getTypeDocument() != null ? document.getTypeDocument().getPlanClassementNoeud() : null;
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

    /**
     * Crée une activité. Le CODE n'est jamais saisi : il est attribué selon la position dans l'arbre — 01, 02, 03…
     * à la racine, puis 01.1, 01.2… sous 01, 01.1.1… sous 01.1 (voir prochainCode).
     */
    @Transactional
    public PlanClassementNoeudDto creer(PlanClassementNoeudRequestDto dto, User currentUser)
    {
        if (dto.getUoId() == null)
        {
            throw new BusinessException("L'unité organisationnelle est obligatoire");
        }
        UniteOrganisationnelle uo = uniteOrganisationnelleService.getUOEntiteSiEditeur(dto.getUoId(), currentUser);

        String libelle = nettoyer(dto.getLibelle(), "Le libellé");

        PlanClassementNoeud parent = null;
        if (dto.getParentId() != null)
        {
            parent = chargerDansUo(dto.getParentId(), uo.getId());
        }
        String code = prochainCode(uo.getId(), parent, Set.of());

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

    /**
     * Crée une activité et toute sa descendance en une fois (organigramme de création). Atomique : une erreur
     * n'en crée aucune. Les codes sont générés nœud par nœud d'après la position.
     */
    @Transactional
    public PlanClassementNoeudDto creerArborescence(PlanClassementArbreRequestDto dto, User currentUser)
    {
        if (dto.getUoId() == null || dto.getNode() == null)
        {
            throw new BusinessException("L'unité organisationnelle et l'activité sont obligatoires");
        }
        UniteOrganisationnelle uo = uniteOrganisationnelleService.getUOEntiteSiEditeur(dto.getUoId(), currentUser);
        PlanClassementNoeud parent = dto.getParentId() != null ? chargerDansUo(dto.getParentId(), uo.getId()) : null;

        int[] compteur = { 0 };
        PlanClassementNoeud racine = creerRecursif(dto.getNode(), parent, uo, compteur);

        auditLogService.log(currentUser, AuditAction.PLAN_CLASSEMENT_NOEUD_CREE, AuditCible.PLAN_CLASSEMENT,
            racine.getId().toString(), uo.getId(),
            "Activité \"" + racine.getCode() + " " + racine.getLibelle() + "\" ajoutée au plan de classement"
                + (compteur[0] > 1 ? " avec " + (compteur[0] - 1) + " sous-activité(s)" : ""), true);

        return trouverDansArbre(uo.getId(), racine.getId());
    }

    private PlanClassementNoeud creerRecursif(PlanClassementArbreRequestDto.Noeud n, PlanClassementNoeud parent,
                                              UniteOrganisationnelle uo, int[] compteur)
    {
        PlanClassementNoeud noeud = new PlanClassementNoeud();
        noeud.setLibelle(nettoyer(n.getLibelle(), "Le libellé"));
        noeud.setCode(prochainCode(uo.getId(), parent, Set.of()));
        noeud.setParent(parent);
        noeud.setUniteOrganisationnelle(uo);
        noeudRepository.saveAndFlush(noeud);   // visible par prochainCode du frère suivant
        compteur[0]++;
        for (PlanClassementArbreRequestDto.Noeud enfant : n.getChildren())
        {
            creerRecursif(enfant, noeud, uo, compteur);
        }
        return noeud;
    }

    /**
     * Met à jour une activité existante et sa descendance en un seul appel : libellés modifiés (refusé pour une
     * activité verrouillée) et nouvelles sous-activités ajoutées. Une activité existante absente de la requête
     * n'est jamais supprimée ici (la suppression reste une action dédiée, avec sa confirmation).
     */
    @Transactional
    public PlanClassementNoeudDto mettreAJourArborescence(Long id, PlanClassementArbreRequestDto.Noeud node, User currentUser)
    {
        if (node == null)
        {
            throw new BusinessException("L'activité est obligatoire");
        }
        PlanClassementNoeud racine = charger(id);
        Long uoId = racine.getUniteOrganisationnelle().getId();
        verifierEditeur(uoId, currentUser);

        Set<Long> sousArbre = idsAvecDescendants(id);
        int[] ajoutes = { 0 };
        List<String> renommes = new ArrayList<>();
        mettreAJourRecursif(node, racine, sousArbre, racine.getUniteOrganisationnelle(), ajoutes, renommes);

        if (!renommes.isEmpty() || ajoutes[0] > 0)
        {
            auditLogService.log(currentUser, AuditAction.PLAN_CLASSEMENT_NOEUD_MODIFIE, AuditCible.PLAN_CLASSEMENT,
                id.toString(), uoId,
                "Activité \"" + racine.getCode() + "\" mise à jour"
                    + (renommes.isEmpty() ? "" : " — renommées : " + String.join(", ", renommes))
                    + (ajoutes[0] > 0 ? " — " + ajoutes[0] + " sous-activité(s) ajoutée(s)" : ""), true);
        }
        return trouverDansArbre(uoId, id);
    }

    private void mettreAJourRecursif(PlanClassementArbreRequestDto.Noeud n, PlanClassementNoeud existant,
                                     Set<Long> sousArbre, UniteOrganisationnelle uo, int[] ajoutes, List<String> renommes)
    {
        String libelle = nettoyer(n.getLibelle(), "Le libellé");
        if (!libelle.equals(existant.getLibelle()))
        {
            verifierNonVerrouille(existant, "renommer");
            renommes.add("\"" + existant.getCode() + " " + existant.getLibelle() + "\" → \"" + libelle + "\"");
            existant.setLibelle(libelle);
            noeudRepository.saveAndFlush(existant);
        }
        for (PlanClassementArbreRequestDto.Noeud enfant : n.getChildren())
        {
            if (enfant.getId() == null)
            {
                int[] c = { 0 };
                creerRecursif(enfant, existant, uo, c);
                ajoutes[0] += c[0];
            }
            else
            {
                if (!sousArbre.contains(enfant.getId()))
                {
                    throw new BusinessException("Activité hors de l'arborescence modifiée : " + enfant.getId());
                }
                mettreAJourRecursif(enfant, charger(enfant.getId()), sousArbre, uo, ajoutes, renommes);
            }
        }
    }

    /** Le nœud demandé, avec sa descendance et ses indicateurs (documents, verrou), tel que getArbre le renvoie. */
    private PlanClassementNoeudDto trouverDansArbre(Long uoId, Long id)
    {
        List<PlanClassementNoeud> tous = noeudRepository.findByUniteOrganisationnelleId(uoId);
        Map<Long, Long> comptes = new HashMap<>();
        for (Object[] r : typeDocumentRepository.countParNoeudPourUo(uoId))
        {
            comptes.put((Long) r[0], (Long) r[1]);
        }
        Map<Long, Long> documents = new HashMap<>();
        for (Object[] r : documentRepository.countDocumentsParActivitePourUo(uoId))
        {
            documents.put((Long) r[0], (Long) r[1]);
        }
        Map<Long, List<PlanClassementNoeud>> enfants = tous.stream()
            .filter(n -> n.getParent() != null)
            .collect(Collectors.groupingBy(n -> n.getParent().getId()));
        PlanClassementNoeud noeud = tous.stream().filter(n -> n.getId().equals(id)).findFirst()
            .orElseThrow(() -> new BusinessException("Activité introuvable : " + id));
        return versDto(noeud, enfants, comptes, documents);
    }

    /** Renomme (libellé seulement : le code découle de la position). Refusé si des documents sont classés ici
     *  ou dans une sous-activité. */
    @Transactional
    public PlanClassementNoeudDto modifier(Long id, PlanClassementNoeudRequestDto dto, User currentUser)
    {
        PlanClassementNoeud noeud = charger(id);
        Long uoId = noeud.getUniteOrganisationnelle().getId();
        verifierEditeur(uoId, currentUser);
        verifierNonVerrouille(noeud, "renommer");

        String libelle = nettoyer(dto.getLibelle(), "Le libellé");

        String avant = noeud.getCode() + " " + noeud.getLibelle();
        noeud.setLibelle(libelle);
        noeudRepository.save(noeud);

        auditLogService.log(currentUser, AuditAction.PLAN_CLASSEMENT_NOEUD_MODIFIE, AuditCible.PLAN_CLASSEMENT,
            id.toString(), uoId,
            "Activité \"" + avant + "\" renommée en \"" + noeud.getCode() + " " + libelle + "\"", true);

        return simple(noeud);
    }

    /** newParentId null = déplacer à la racine du plan. L'activité et ses sous-activités reçoivent de nouveaux
     *  codes, calculés d'après leur nouvelle position. Refusé si des documents y sont classés. */
    @Transactional
    public PlanClassementNoeudDto deplacer(Long id, Long newParentId, User currentUser)
    {
        PlanClassementNoeud noeud = charger(id);
        Long uoId = noeud.getUniteOrganisationnelle().getId();
        verifierEditeur(uoId, currentUser);

        PlanClassementNoeud nouveauParent = null;
        Set<Long> sousArbre = idsAvecDescendants(id);
        if (newParentId != null)
        {
            nouveauParent = chargerDansUo(newParentId, uoId);
            if (sousArbre.contains(newParentId))
            {
                throw new BusinessException("Impossible de déplacer une activité sous elle-même ou sous l'une de ses sous-activités");
            }
        }
        verifierNonVerrouille(noeud, "déplacer");

        String avant = noeud.getCode();
        noeud.setParent(nouveauParent);
        noeud.setCode(prochainCode(uoId, nouveauParent, sousArbre));
        noeudRepository.saveAndFlush(noeud);
        renumeroterDescendants(noeud);

        auditLogService.log(currentUser, AuditAction.PLAN_CLASSEMENT_NOEUD_DEPLACE, AuditCible.PLAN_CLASSEMENT,
            id.toString(), uoId,
            "Activité \"" + avant + " " + noeud.getLibelle() + "\" déplacée "
                + (nouveauParent != null ? "sous \"" + nouveauParent.getCode() + " " + nouveauParent.getLibelle() + "\"" : "à la racine")
                + " (nouveau code : " + noeud.getCode() + ")",
            true);

        return simple(noeud);
    }

    /**
     * Supprime une activité ET toutes ses sous-activités, du moment qu'aucun document n'est classé dedans (ni la
     * leur, ni via leur type). Les types de documents rattachés à l'une d'elles — forcément sans document — sont
     * simplement détachés.
     */
    @Transactional
    public void supprimer(Long id, User currentUser)
    {
        PlanClassementNoeud noeud = charger(id);
        Long uoId = noeud.getUniteOrganisationnelle().getId();
        verifierEditeur(uoId, currentUser);

        Set<Long> sousArbre = idsAvecDescendants(id);
        if (documentRepository.compterDocumentsDansActivites(sousArbre) > 0)
        {
            throw new BusinessException("Des documents sont classés dans cette activité"
                + (sousArbre.size() > 1 ? " ou l'une de ses sous-activités" : "")
                + " — reclassez-les d'abord");
        }

        List<TypeDocument> types = typeDocumentRepository.findByPlanClassementNoeudIdIn(sousArbre);
        types.forEach(t -> t.setPlanClassementNoeud(null));
        typeDocumentRepository.saveAll(types);

        // Les feuilles d'abord : la clé étrangère parent_id interdit de supprimer un parent encore référencé.
        List<PlanClassementNoeud> aSupprimer = new ArrayList<>(noeudRepository.findAllById(sousArbre));
        aSupprimer.sort(Comparator.comparingInt((PlanClassementNoeud n) -> profondeur(n)).reversed());
        for (PlanClassementNoeud n : aSupprimer)
        {
            noeudRepository.delete(n);
            noeudRepository.flush();
        }

        String libelle = noeud.getCode() + " " + noeud.getLibelle();
        auditLogService.log(currentUser, AuditAction.PLAN_CLASSEMENT_NOEUD_SUPPRIME, AuditCible.PLAN_CLASSEMENT,
            id.toString(), uoId, "Activité \"" + libelle + "\" supprimée du plan de classement"
                + (sousArbre.size() > 1 ? " avec ses " + (sousArbre.size() - 1) + " sous-activité(s)" : "")
                + (types.isEmpty() ? "" : " — " + types.size() + " type(s) de document détaché(s)"), true);
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
        made.archive.service.document.TypeDocumentService.refuserSiSysteme(type);

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

    private void verifierNonVerrouille(PlanClassementNoeud noeud, String action)
    {
        if (documentRepository.compterDocumentsDansActivites(idsAvecDescendants(noeud.getId())) > 0)
        {
            throw new BusinessException("Impossible de " + action + " cette activité : des documents y sont classés "
                + "(ou dans l'une de ses sous-activités). Son code et son libellé figurent dans leurs exports.");
        }
    }

    private int profondeur(PlanClassementNoeud n)
    {
        int p = 0;
        Set<Long> vus = new HashSet<>();
        for (PlanClassementNoeud c = n.getParent(); c != null && vus.add(c.getId()); c = c.getParent())
        {
            p++;
        }
        return p;
    }

    /**
     * Prochain code libre pour un enfant de {@code parent} (null = racine) : le plus grand rang existant + 1.
     * Racine : 01, 02, 03… (au moins deux chiffres). Sous un parent de code "01.1" : 01.1.1, 01.1.2…
     * Un rang supprimé en dernier est réattribué (le code d'une activité supprimée n'existe plus nulle part) ; un
     * ancien code libre ("FIN") sert tel quel de préfixe à ses enfants ("FIN.1"). {@code exclus} : nœuds ignorés
     * (ceux qu'on est en train de déplacer).
     */
    String prochainCode(Long uoId, PlanClassementNoeud parent, Set<Long> exclus)
    {
        List<PlanClassementNoeud> tous = noeudRepository.findByUniteOrganisationnelleId(uoId).stream()
            .filter(n -> !exclus.contains(n.getId())).toList();
        Set<String> pris = tous.stream().map(n -> n.getCode().toLowerCase()).collect(Collectors.toSet());

        String prefixe = parent != null ? parent.getCode() + "." : "";
        int max = 0;
        for (PlanClassementNoeud n : tous)
        {
            boolean frere = parent == null ? n.getParent() == null
                : n.getParent() != null && n.getParent().getId().equals(parent.getId());
            if (!frere) continue;
            String reste = n.getCode().startsWith(prefixe) ? n.getCode().substring(prefixe.length()) : "";
            if (reste.matches("\\d{1,9}"))
            {
                max = Math.max(max, Integer.parseInt(reste));
            }
        }

        int rang = max + 1;
        String code;
        do
        {
            code = prefixe + (parent == null ? String.format("%02d", rang) : String.valueOf(rang));
            rang++;
        }
        while (pris.contains(code.toLowerCase()));
        return code;
    }

    /** Après un déplacement : recalcule le code de chaque descendant d'après le nouveau code de son parent. */
    private void renumeroterDescendants(PlanClassementNoeud parent)
    {
        List<PlanClassementNoeud> enfants = noeudRepository.findByUniteOrganisationnelleId(
                parent.getUniteOrganisationnelle().getId()).stream()
            .filter(n -> n.getParent() != null && n.getParent().getId().equals(parent.getId()))
            .sorted(parCode())
            .toList();
        int rang = 1;
        for (PlanClassementNoeud enfant : enfants)
        {
            enfant.setCode(parent.getCode() + "." + rang++);
            noeudRepository.saveAndFlush(enfant);
            renumeroterDescendants(enfant);
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
