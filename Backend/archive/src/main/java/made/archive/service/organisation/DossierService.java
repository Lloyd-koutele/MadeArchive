package made.archive.service.organisation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.ChangerAccesRequestDto;
import made.archive.dto.DossierArbreDto;
import made.archive.dto.DossierDeplacementPreviewDto;
import made.archive.dto.GroupeMembresDto;
import made.archive.dto.DossierDetailDto;
import made.archive.dto.DossierDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.GroupeAccess;
import made.archive.entite.Dossier;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeAccess;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.GroupeAccessRepository;
import made.archive.repository.DossierRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.document.MeilisearchService;
import made.archive.service.notification.NotificationService;
import made.archive.util.NormalisationNoms;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static made.archive.entite.NotificationType.DOSSIER_CREE;

/**
 * Un dossier est un conteneur qui regroupe des documents (dossier/affaire).
 * Voir made.archive.entite.Dossier.
 *
 * Modèle de propriété : le DOSSIER est entièrement piloté par des EDITOR — sa
 * création, exclusivement par le créateur au départ. Pour un dossier PUBLIC,
 * n'importe quel éditeur de sa propre UO peut ensuite agir (types attendus,
 * suppression si vide) — voir peutGererDossier. Pour un dossier PRIVÉ, il faut
 * en plus être membre de son GroupeAccess : un éditeur de l'UO qui n'est pas
 * membre n'a aucun droit dessus, ni même de le lister/consulter (voir
 * estVisiblePour). Le créateur reste protégé (jamais retirable de son propre
 * groupe) mais n'est plus seul à pouvoir gérer — ça évite qu'un dossier privé
 * se retrouve figé si son créateur quitte l'UO : les autres membres-éditeurs
 * prennent le relais (voir peutGererGroupeDossier).
 *
 * ADMIN et ADMIN_UO n'ont eux aucun droit d'écriture sur les dossiers,
 * uniquement un droit de lecture, lui-même soumis aux mêmes règles de
 * confidentialité que tout le monde — aucun contournement de rôle, y compris
 * dans les listes/recherches, pour éviter toute fuite d'un dossier privé.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DossierService
{
    private final DossierRepository                dossierRepository;
    private final UniteOrganisationnelleRepository uoRepository;
    private final TypeDocumentRepository           typeDocumentRepository;
    private final DocumentRepository               documentRepository;
    private final UserRepository                   userRepository;
    private final GroupeAccessRepository           groupeAccessRepository;
    private final UniteOrganisationnelleService    uniteOrganisationnelleService;
    private final NotificationService              notificationService;
    private final AuditLogService                  auditLogService;
    private final MeilisearchService               meilisearchService;
    private final made.archive.repository.PhysicalLocationRepository physicalLocationRepository;

    // ═══════════════════════════════════════════════════════════════════
    // Création
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public Dossier creerDossier(DossierDto dto, User createur)
    {
        if (dto.getNom() == null || dto.getNom().isBlank())
        {
            throw new BusinessException("Le nom du dossier est obligatoire");
        }
        if (dto.getUoId() == null)
        {
            throw new BusinessException("L'unité organisationnelle est obligatoire");
        }

        UniteOrganisationnelle uo = uoRepository.findById(dto.getUoId())
            .orElseThrow(() -> new BusinessException(
                "Unité organisationnelle introuvable : " + dto.getUoId()));

        verifierEstEditeurDeUO(uo.getId(), createur);

        Dossier parent = null;
        if (dto.getParentId() != null)
        {
            parent = dossierRepository.findById(dto.getParentId())
                .orElseThrow(() -> new BusinessException("Dossier parent introuvable : " + dto.getParentId()));
            if (!parent.getUniteOrganisationnelle().getId().equals(uo.getId()))
            {
                throw new BusinessException("Le sous-dossier doit appartenir à la même UO que son parent");
            }
            // Créer un sous-dossier, c'est agir sur le parent — si celui-ci est
            // privé, seul un éditeur membre de son groupe peut y ajouter quoi
            // que ce soit (même porte que toute autre action sur ce parent).
            verifierPeutGererDossier(parent, createur);
        }

        verifierNomUnique(dto.getNom(), uo.getId(), parent, null);

        List<TypeDocument> typesAttendus = resoudreTypesAttendus(dto.getTypeDocumentIds(), uo);

        TypeAccess accesDemande = "PRIVE".equalsIgnoreCase(dto.getAccess()) ? TypeAccess.PRIVE : TypeAccess.PUBLIC;
        // INVARIANT de confidentialité : un enfant ne peut jamais être plus
        // ouvert que son parent — un parent PRIVÉ force l'enfant PRIVÉ, quel
        // que soit ce qui était demandé (voir Javadoc de Dossier.parent).
        boolean parentPrive = parent != null && parent.getAccess() == TypeAccess.PRIVE;
        TypeAccess access = parentPrive ? TypeAccess.PRIVE : accesDemande;

        // Groupe d'accès créé UNE SEULE FOIS ici si le dossier est privé — tout
        // document versé dedans réutilisera CE MÊME groupe (voir
        // DocumentUploadeService), jamais un groupe recréé par document.
        // Sous un parent PRIVÉ, hérite de TOUS ses membres actuels EN PLUS du
        // créateur et des membres explicitement demandés (dto.groupeMembresIds
        // s'ajoute, ne remplace jamais l'héritage) — voir Javadoc de classe.
        GroupeAccess groupe = null;
        if (access == TypeAccess.PRIVE)
        {
            List<User> herites = parentPrive && parent.getGroupe() != null
                ? parent.getGroupe().getMembres() : null;
            groupe = creerGroupeAvecMembres(createur, herites, dto.getGroupeMembresIds());
        }

        Dossier dossier = new Dossier();
        dossier.setNom(dto.getNom());
        dossier.setUniteOrganisationnelle(uo);
        dossier.setParent(parent);
        dossier.setCreePar(createur);
        dossier.setCreateAt(LocalDateTime.now());
        dossier.setTypesDocumentsAttendus(typesAttendus);
        dossier.setAccess(access);
        dossier.setGroupe(groupe);

        Dossier saved = dossierRepository.save(dossier);
        log.info("[Dossier] '{}' créé dans l'UO {} par {} ({}, {} type(s) attendu(s))",
            saved.getNom(), uo.getId(), createur.getEmail(), access, typesAttendus.size());

        auditLogService.log(createur, AuditAction.DOSSIER_CREE, AuditCible.DOSSIER,
            saved.getId().toString(), uo.getId(),
            "Création du dossier " + saved.getNom() + " dans l'UO " + uo.getNom()
                + (parent != null ? " sous \"" + parent.getNom() + "\"" : ""), true);

        notifierCreationDossier(saved, uo, createur, access, groupe);

        return saved;
    }

    /**
     * Construit un GroupeAccess : le créateur/acteur, plus les membres
     * HÉRITÉS d'un parent privé le cas échéant (herites, peut être null),
     * plus les membres explicitement demandés (membresSupplementairesIds,
     * peut être null/vide) — dédoublonné par id, jamais de doublon même si
     * un même utilisateur apparaît dans plusieurs de ces trois sources.
     */
    private GroupeAccess creerGroupeAvecMembres(User acteur, List<User> herites, List<UUID> membresSupplementairesIds)
    {
        GroupeAccess g = new GroupeAccess();
        g.setCreateAt(LocalDate.now());

        List<User> membres = new ArrayList<>();
        Set<UUID> idsAjoutes = new LinkedHashSet<>();

        if (herites != null)
        {
            for (User m : herites)
            {
                if (idsAjoutes.add(m.getId()))
                {
                    membres.add(m);
                }
            }
        }
        if (idsAjoutes.add(acteur.getId()))
        {
            membres.add(acteur);
        }
        if (membresSupplementairesIds != null && !membresSupplementairesIds.isEmpty())
        {
            for (User m : userRepository.findAllById(membresSupplementairesIds))
            {
                if (idsAjoutes.add(m.getId()))
                {
                    membres.add(m);
                }
            }
        }

        g.setMembres(membres);
        return groupeAccessRepository.save(g);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Modification — nom uniquement (les types attendus se gèrent via
    // ajouterTypesAttendus/retirerTypeAttendu ci-dessous, la bascule
    // PUBLIC↔PRIVÉ via modifierAcces plus bas, la gestion des MEMBRES d'un
    // groupe déjà PRIVÉ via DossierGroupeAccessController — jamais mélangés
    // dans le même appel).
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public Dossier modifierDossier(Long dossierId, String nom, User acteur)
    {
        Dossier dossier = dossierRepository.findById(dossierId)
            .orElseThrow(() -> new BusinessException("Dossier introuvable : " + dossierId));

        verifierPeutGererDossier(dossier, acteur);

        if (nom == null || nom.isBlank())
        {
            throw new BusinessException("Le nom du dossier est obligatoire");
        }
        // Le formulaire "Modifier" renvoie toujours le nom, même quand seuls les types attendus changent : le verrou
        // ne s'applique que si le nom change réellement.
        if (!nom.equals(dossier.getNom()))
        {
            verifierBrancheSansDocument(dossier, "renommer");
        }

        Long uoId = dossier.getUniteOrganisationnelle().getId();
        verifierNomUnique(nom, uoId, dossier.getParent(), dossierId);

        dossier.setNom(nom);
        Dossier saved = dossierRepository.save(dossier);

        auditLogService.log(acteur, AuditAction.DOSSIER_MODIFIE, AuditCible.DOSSIER,
            dossierId.toString(), uoId, "Modification du dossier " + saved.getNom(), true);

        return saved;
    }

    /**
     * Bascule PUBLIC ↔ PRIVÉ après coup — même autorité que modifierDossier/
     * ajouterTypesAttendus/supprimerDossier (voir verifierPeutGererDossier :
     * éditeur de la propre UO si actuellement PUBLIC, éditeur membre du
     * groupe si déjà PRIVÉ).
     *
     * Fait suivre le changement aux documents du dossier qui partagent (ou
     * vont partager) son GroupeAccess — jamais ceux ayant leur propre
     * confidentialité indépendante (rendue privée séparément via son propre
     * bouton d'accès, voir DocumentService.modifierAcces) :
     *   - PUBLIC → PRIVÉ : crée un nouveau GroupeAccess (acteur + membres
     *     optionnels, même logique qu'à la création — voir creerDossier),
     *     puis tout document du dossier actuellement PUBLIC (donc sans
     *     groupe propre, invariant Document.groupe) bascule PRIVÉ avec CE
     *     MÊME groupe — cohérent avec DocumentUploadeService, qui fait
     *     exactement ça pour un document uploadé directement dans un
     *     dossier privé. TOUT sous-dossier ENCORE public bascule aussi,
     *     OBLIGATOIREMENT et récursivement (voir cascaderVersPrive) — jamais
     *     un enfant plus ouvert que son parent, ce cas-ci n'admet pas
     *     d'exception (contrairement au sens inverse ci-dessous).
     *   - PRIVÉ → PUBLIC : détache le groupe du dossier (jamais supprimé,
     *     même principe que DocumentService.modifierAcces), puis tout
     *     document qui partageait ENCORE ce même groupe (identité de
     *     référence, pas juste "était PRIVÉ") redevient PUBLIC lui aussi.
     *     Les sous-dossiers, eux, NE CHANGENT PAS ici (un enfant privé sous
     *     un parent public reste un état valide) — pour les rendre publics
     *     aussi, voir cascaderAccesPublicVersDescendants, un appel séparé et
     *     délibéré (jamais automatique).
     */
    @Transactional
    public Dossier modifierAcces(Long dossierId, ChangerAccesRequestDto dto, User acteur)
    {
        Dossier dossier = dossierRepository.findById(dossierId)
            .orElseThrow(() -> new BusinessException("Dossier introuvable : " + dossierId));

        verifierPeutGererDossier(dossier, acteur);

        TypeAccess ancienAcces = dossier.getAccess();
        if (dto.getAccess() == null || dto.getAccess() == ancienAcces)
        {
            return dossier;
        }

        // INVARIANT (voir Javadoc de Dossier.parent) : ne devrait déjà jamais
        // se produire (la création empêche un enfant public sous un parent
        // privé), revérifié ici par prudence avant d'accepter un passage à
        // PUBLIC.
        if (dto.getAccess() == TypeAccess.PUBLIC && dossier.getParent() != null
            && dossier.getParent().getAccess() == TypeAccess.PRIVE)
        {
            throw new BusinessException(
                "Impossible : le dossier parent \"" + dossier.getParent().getNom()
                    + "\" est privé, ce sous-dossier ne peut pas devenir public");
        }

        List<Document> documentsDuDossier = documentRepository.findByDossierId(dossierId).stream()
            .filter(d -> d.getStatus() != DocumentStatus.DELETED)
            .toList();

        if (dto.getAccess() == TypeAccess.PRIVE)
        {
            GroupeAccess nouveauGroupe = creerGroupeAvecMembres(acteur, null, dto.getGroupeMembresIds());
            dossier.setGroupe(nouveauGroupe);

            for (Document doc : documentsDuDossier)
            {
                if (doc.getAccess() == TypeAccess.PUBLIC)
                {
                    doc.setAccess(TypeAccess.PRIVE);
                    doc.setGroupe(nouveauGroupe);
                    documentRepository.save(doc);
                    meilisearchService.updateDocumentAccess(doc);
                }
            }

            cascaderVersPrive(dossier, nouveauGroupe, acteur);
        }
        else
        {
            Long ancienGroupeId = dossier.getGroupe() != null ? dossier.getGroupe().getId() : null;
            dossier.setGroupe(null);

            if (ancienGroupeId != null)
            {
                for (Document doc : documentsDuDossier)
                {
                    if (doc.getGroupe() != null && ancienGroupeId.equals(doc.getGroupe().getId()))
                    {
                        doc.setAccess(TypeAccess.PUBLIC);
                        doc.setGroupe(null);
                        documentRepository.save(doc);
                        meilisearchService.updateDocumentAccess(doc);
                    }
                }
            }
        }

        dossier.setAccess(dto.getAccess());
        Dossier saved = dossierRepository.save(dossier);

        auditLogService.log(acteur, AuditAction.DOSSIER_ACCES_MODIFIE, AuditCible.DOSSIER,
            dossierId.toString(), dossier.getUniteOrganisationnelle().getId(),
            "Accès du dossier " + saved.getNom() + " changé de " + ancienAcces + " à " + dto.getAccess(),
            true);

        return saved;
    }

    /**
     * PUBLIC → PRIVÉ (voir modifierAcces) : force récursivement tout
     * sous-dossier ENCORE public à devenir privé, avec un groupe INDÉPENDANT
     * (jamais le même objet que "parent" — modifiable séparément par la
     * suite), initialisé avec les MÊMES membres que le groupe du parent au
     * moment du passage. Un sous-dossier déjà privé garde son propre groupe
     * intact (rien à faire), mais on continue quand même à descendre dans sa
     * propre descendance par prudence.
     */
    private void cascaderVersPrive(Dossier parent, GroupeAccess groupeParent, User acteur)
    {
        for (Dossier enfant : dossierRepository.findByParentId(parent.getId()))
        {
            if (enfant.getAccess() == TypeAccess.PUBLIC)
            {
                GroupeAccess groupeEnfant = creerGroupeAvecMembres(acteur, groupeParent.getMembres(), null);
                enfant.setAccess(TypeAccess.PRIVE);
                enfant.setGroupe(groupeEnfant);
                dossierRepository.save(enfant);

                for (Document doc : documentRepository.findByDossierId(enfant.getId()))
                {
                    if (doc.getStatus() != DocumentStatus.DELETED && doc.getAccess() == TypeAccess.PUBLIC)
                    {
                        doc.setAccess(TypeAccess.PRIVE);
                        doc.setGroupe(groupeEnfant);
                        documentRepository.save(doc);
                        meilisearchService.updateDocumentAccess(doc);
                    }
                }

                auditLogService.log(acteur, AuditAction.DOSSIER_ACCES_MODIFIE, AuditCible.DOSSIER,
                    enfant.getId().toString(), enfant.getUniteOrganisationnelle().getId(),
                    "Dossier \"" + enfant.getNom() + "\" rendu privé automatiquement (parent \""
                        + parent.getNom() + "\" devenu privé)", true);

                cascaderVersPrive(enfant, groupeEnfant, acteur);
            }
            else
            {
                cascaderVersPrive(enfant, enfant.getGroupe() != null ? enfant.getGroupe() : groupeParent, acteur);
            }
        }
    }

    /**
     * Cascade OPTIONNELLE PRIVÉ → PUBLIC vers toute la descendance — JAMAIS
     * appelée automatiquement par modifierAcces (voir sa Javadoc), seulement
     * si l'éditeur confirme explicitement (modal d'alerte côté client) après
     * avoir rendu ce dossier public. Ne fait rien sur un dossier déjà public.
     */
    @Transactional
    public Dossier cascaderAccesPublicVersDescendants(Long dossierId, User acteur)
    {
        Dossier dossier = dossierRepository.findById(dossierId)
            .orElseThrow(() -> new BusinessException("Dossier introuvable : " + dossierId));

        verifierPeutGererDossier(dossier, acteur);

        if (dossier.getAccess() != TypeAccess.PUBLIC)
        {
            throw new BusinessException("Ce dossier doit déjà être public pour cascader vers ses sous-dossiers");
        }

        cascaderVersPublic(dossier, acteur);

        return dossier;
    }

    private void cascaderVersPublic(Dossier parent, User acteur)
    {
        for (Dossier enfant : dossierRepository.findByParentId(parent.getId()))
        {
            if (enfant.getAccess() == TypeAccess.PRIVE)
            {
                Long ancienGroupeId = enfant.getGroupe() != null ? enfant.getGroupe().getId() : null;
                enfant.setAccess(TypeAccess.PUBLIC);
                enfant.setGroupe(null);
                dossierRepository.save(enfant);

                if (ancienGroupeId != null)
                {
                    for (Document doc : documentRepository.findByDossierId(enfant.getId()))
                    {
                        if (doc.getStatus() != DocumentStatus.DELETED && doc.getGroupe() != null
                            && ancienGroupeId.equals(doc.getGroupe().getId()))
                        {
                            doc.setAccess(TypeAccess.PUBLIC);
                            doc.setGroupe(null);
                            documentRepository.save(doc);
                            meilisearchService.updateDocumentAccess(doc);
                        }
                    }
                }

                auditLogService.log(acteur, AuditAction.DOSSIER_ACCES_MODIFIE, AuditCible.DOSSIER,
                    enfant.getId().toString(), enfant.getUniteOrganisationnelle().getId(),
                    "Dossier \"" + enfant.getNom() + "\" rendu public en cascade (parent \""
                        + parent.getNom() + "\")", true);
            }

            cascaderVersPublic(enfant, acteur);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Déplacement — glisser-déposer un dossier vers un nouveau parent (ou
    // vers la racine de l'UO). Même invariant de confidentialité qu'à la
    // création (voir Dossier.parent) : un enfant ne peut jamais être plus
    // ouvert que son parent.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Aperçu (dry-run, ne modifie rien) à appeler AVANT deplacerDossier pour
     * afficher un modal d'alerte côté client si le déplacement va forcer ce
     * dossier PRIVÉ, ou si son propre groupe diverge de celui du nouveau
     * parent (voir DossierDeplacementPreviewDto).
     */
    @Transactional(readOnly = true)
    public DossierDeplacementPreviewDto previsualiserDeplacement(Long dossierId, Long nouveauParentId, User acteur)
    {
        Dossier dossier = dossierRepository.findById(dossierId)
            .orElseThrow(() -> new BusinessException("Dossier introuvable : " + dossierId));
        verifierPeutGererDossier(dossier, acteur);

        Dossier nouveauParent = resoudreEtValiderNouveauParent(dossier, nouveauParentId, acteur);

        boolean deviendraPrive = dossier.getAccess() == TypeAccess.PUBLIC
            && nouveauParent != null && nouveauParent.getAccess() == TypeAccess.PRIVE;

        boolean divergence = false;
        List<User> divergents = List.of();
        if (dossier.getAccess() == TypeAccess.PRIVE && nouveauParent != null
            && nouveauParent.getAccess() == TypeAccess.PRIVE
            && dossier.getGroupe() != null && nouveauParent.getGroupe() != null)
        {
            Set<UUID> membresParent = nouveauParent.getGroupe().getMembres().stream()
                .map(User::getId)
                .collect(java.util.stream.Collectors.toSet());
            divergents = dossier.getGroupe().getMembres().stream()
                .filter(m -> !membresParent.contains(m.getId()))
                .toList();
            divergence = !divergents.isEmpty();
        }

        return DossierDeplacementPreviewDto.builder()
            .deviendraPrive(deviendraPrive)
            .divergenceGroupes(divergence)
            .membresDivergents(divergents)
            .build();
    }

    /**
     * Déplace un dossier vers un nouveau parent (null = racine de l'UO) — à
     * appeler après confirmation du client sur l'aperçu de
     * previsualiserDeplacement s'il signalait un changement. Invariant de
     * confidentialité (voir Dossier.parent) :
     *   - PUBLIC déplacé sous un parent PRIVÉ : forcé PRIVÉ, nouveau groupe
     *     hérité des membres du parent (même logique qu'à la création via
     *     creerDossier), documents publics du dossier et sous-dossiers
     *     encore publics cascadés PRIVÉ (voir cascaderVersPrive).
     *   - PRIVÉ déplacé sous un parent PRIVÉ : garde SON PROPRE groupe
     *     intact (jamais remplacé ni fusionné), mais chacun de ses membres
     *     absent du groupe du nouveau parent y est ajouté, en remontant la
     *     chaîne d'ancêtres privés du nouveau parent (voir
     *     propagerMembreVersAncetres) — préserve l'invariant de
     *     navigabilité sans jamais perdre l'accès qu'avait ce dossier.
     *   - PRIVÉ ou PUBLIC déplacé sous un parent PUBLIC (ou vers la racine) :
     *     aucun changement d'accès, état déjà valide.
     */
    @Transactional
    public Dossier deplacerDossier(Long dossierId, Long nouveauParentId, User acteur)
    {
        Dossier dossier = dossierRepository.findById(dossierId)
            .orElseThrow(() -> new BusinessException("Dossier introuvable : " + dossierId));
        verifierPeutGererDossier(dossier, acteur);

        Dossier nouveauParent = resoudreEtValiderNouveauParent(dossier, nouveauParentId, acteur);

        Long ancienParentId = dossier.getParent() != null ? dossier.getParent().getId() : null;
        if ((ancienParentId == null && nouveauParentId == null)
            || (ancienParentId != null && ancienParentId.equals(nouveauParentId)))
        {
            return dossier;
        }
        verifierBrancheSansDocument(dossier, "déplacer");

        // Un déplacement peut faire atterrir le dossier parmi de nouveaux
        // frères/sœurs — même vérification qu'à la création, sur la MÊME UO
        // (le déplacement ne change jamais d'UO, voir resoudreEtValiderNouveauParent).
        verifierNomUnique(dossier.getNom(), dossier.getUniteOrganisationnelle().getId(), nouveauParent, dossierId);

        if (dossier.getAccess() == TypeAccess.PUBLIC
            && nouveauParent != null && nouveauParent.getAccess() == TypeAccess.PRIVE)
        {
            List<User> heritesDuParent = nouveauParent.getGroupe() != null
                ? nouveauParent.getGroupe().getMembres() : null;
            GroupeAccess nouveauGroupe = creerGroupeAvecMembres(acteur, heritesDuParent, null);
            dossier.setAccess(TypeAccess.PRIVE);
            dossier.setGroupe(nouveauGroupe);

            for (Document doc : documentRepository.findByDossierId(dossierId))
            {
                if (doc.getStatus() != DocumentStatus.DELETED && doc.getAccess() == TypeAccess.PUBLIC)
                {
                    doc.setAccess(TypeAccess.PRIVE);
                    doc.setGroupe(nouveauGroupe);
                    documentRepository.save(doc);
                    meilisearchService.updateDocumentAccess(doc);
                }
            }

            cascaderVersPrive(dossier, nouveauGroupe, acteur);
        }
        else if (dossier.getAccess() == TypeAccess.PRIVE
            && nouveauParent != null && nouveauParent.getAccess() == TypeAccess.PRIVE
            && dossier.getGroupe() != null)
        {
            for (User membre : dossier.getGroupe().getMembres())
            {
                propagerMembreVersAncetres(nouveauParent, membre, acteur.getId());
            }
        }

        dossier.setParent(nouveauParent);
        Dossier saved = dossierRepository.save(dossier);

        auditLogService.log(acteur, AuditAction.DOSSIER_DEPLACE, AuditCible.DOSSIER,
            dossierId.toString(), dossier.getUniteOrganisationnelle().getId(),
            "Déplacement du dossier \"" + saved.getNom() + "\" "
                + (nouveauParent != null ? "sous \"" + nouveauParent.getNom() + "\"" : "vers la racine"),
            true);

        return saved;
    }

    /**
     * Résout et valide le nouveau parent (null = racine, toujours valide) :
     * doit exister, appartenir à la MÊME UO que le dossier déplacé, ne pas
     * être le dossier lui-même ni l'un de ses propres descendants (créerait
     * un cycle), et l'acteur doit avoir l'autorité d'y agir (même porte que
     * creerDossier pour un sous-dossier — un parent privé n'accepte de
     * nouveaux enfants que de la part d'un membre de son groupe).
     */
    private Dossier resoudreEtValiderNouveauParent(Dossier dossier, Long nouveauParentId, User acteur)
    {
        if (nouveauParentId == null)
        {
            return null;
        }
        if (nouveauParentId.equals(dossier.getId()))
        {
            throw new BusinessException("Un dossier ne peut pas être son propre parent");
        }

        Dossier nouveauParent = dossierRepository.findById(nouveauParentId)
            .orElseThrow(() -> new BusinessException("Dossier cible introuvable : " + nouveauParentId));

        if (!nouveauParent.getUniteOrganisationnelle().getId().equals(dossier.getUniteOrganisationnelle().getId()))
        {
            throw new BusinessException("Le dossier cible doit appartenir à la même UO");
        }
        if (estLuiMemeOuDescendantDe(nouveauParent, dossier))
        {
            throw new BusinessException(
                "Impossible : ce dossier ne peut pas devenir enfant de l'un de ses propres descendants");
        }

        verifierPeutGererDossier(nouveauParent, acteur);

        return nouveauParent;
    }

    private boolean estLuiMemeOuDescendantDe(Dossier cible, Dossier dossier)
    {
        Dossier courant = cible;
        while (courant != null)
        {
            if (courant.getId().equals(dossier.getId()))
            {
                return true;
            }
            courant = courant.getParent();
        }
        return false;
    }

    private void notifierCreationDossier(
        Dossier dossier, UniteOrganisationnelle uo, User createur, TypeAccess access, GroupeAccess groupe)
    {
        try
        {
            List<User> destinataires = new ArrayList<>();
            if (access == TypeAccess.PUBLIC)
            {
                // Tous les membres de l'UO + les ADMIN_UO ayant autorité (ces
                // derniers peuvent être en dehors de l'UO elle-même, s'ils sont
                // responsables d'une UO ancêtre) + les ADMIN globaux.
                destinataires.addAll(userRepository.findByUniteOrganisationnelleId(uo.getId()));
                destinataires.addAll(uniteOrganisationnelleService.getAdminUOAvecAutoriteSur(uo.getId()));
                destinataires.addAll(userRepository.findByRoleName(Role_Name.ADMIN));
            }
            else
            {
                // Dossier PRIVÉ : ne notifier QUE les membres du groupe — notifier
                // toute l'UO ferait fuiter l'existence et le nom du dossier à des
                // personnes qui n'y ont justement pas accès.
                destinataires.addAll(groupe.getMembres());
            }

            notificationService.notifier(destinataires, DOSSIER_CREE,
                "Un nouveau dossier \"" + dossier.getNom() + "\" a été créé dans l'unité organisationnelle \""
                    + uo.getNom() + "\" par " + createur.getPrenom() + " " + createur.getNom() + ".");
        }
        catch (Exception e)
        {
            log.warn("[Dossier] Notification (best-effort) échouée pour le dossier {} : {}",
                dossier.getId(), e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lecture — scopée par UO ET par confidentialité, jamais de contournement
    // de rôle (voir Javadoc de la classe)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * parentId null = dossiers racine de l'UO ; sinon les enfants DIRECTS de
     * ce dossier précis — jamais toute l'UO à plat (voir Dossier.parent).
     */
    @Transactional(readOnly = true)
    public List<Dossier> getDossiersDeUO(Long uoId, Long parentId, User currentUser)
    {
        Set<Long> uoVisibles = uniteOrganisationnelleService.getUoIdsVisiblesPourLecture(currentUser);
        if (uoVisibles != null && !uoVisibles.contains(uoId))
        {
            // Hors périmètre : liste vide plutôt qu'une exception — ne confirme
            // ni n'infirme l'existence de l'UO à quelqu'un qui n'y a pas accès.
            return List.of();
        }

        List<Dossier> dossiers = parentId == null
            ? dossierRepository.findByParentIsNullAndUniteOrganisationnelleId(uoId)
            : dossierRepository.findByParentId(parentId);

        List<Dossier> visibles = dossiers.stream()
            .filter(d -> d.getUniteOrganisationnelle().getId().equals(uoId))
            .filter(p -> estVisiblePour(p, currentUser, uoVisibles))
            .toList();
        marquerBranchesVerrouillees(uoId, visibles);
        return visibles;
    }

    /** Un dossier est verrouillé si lui ou l'un de ses descendants contient des documents — un seul passage pour toute l'UO. */
    private void marquerBranchesVerrouillees(Long uoId, List<Dossier> dossiers)
    {
        if (dossiers.isEmpty()) return;
        Set<Long> avecDocuments = new java.util.HashSet<>(documentRepository.findDossierIdsAvecDocumentsPourUo(uoId));
        if (avecDocuments.isEmpty()) return;

        // Un dossier avec documents verrouille tous ses ancêtres : on remonte la chaîne depuis chacun.
        Map<Long, Dossier> parId = new HashMap<>();
        dossierRepository.findByUniteOrganisationnelleId(uoId).forEach(d -> parId.put(d.getId(), d));
        Set<Long> verrouilles = new java.util.HashSet<>();
        for (Long id : avecDocuments)
        {
            for (Dossier d = parId.get(id); d != null && verrouilles.add(d.getId());
                 d = d.getParent() != null ? parId.get(d.getParent().getId()) : null)
            {
                // remonte jusqu'à la racine
            }
        }
        dossiers.forEach(d -> d.setVerrouille(verrouilles.contains(d.getId())));
    }

    /**
     * Arbre COMPLET des dossiers d'une UO, à plat (contrairement à
     * getDossiersDeUO, scopé par niveau) — un seul aller-retour pour
     * construire un sélecteur pliable/dépliable côté client (voir
     * DossierArbreDto), typiquement pour choisir un dossier cible à
     * l'archivage. Filtré par peutGererDossier (PAS estVisiblePour) : ne
     * propose que les dossiers où cet éditeur a réellement l'autorité d'agir
     * (même porte que créer un sous-dossier ou déclarer un type attendu) —
     * un dossier simplement VISIBLE (ex. ADMIN_UO consultant une UO
     * descendante, ou un dossier privé dont on n'est pas membre) n'a pas sa
     * place dans un sélecteur de dossier CIBLE. Le filtre d'orphelin ne se
     * pose pas ici : un nœud filtré peut laisser des enfants orphelins dans
     * l'arbre reconstruit côté client, contrairement à getDossiersDeUO — sans
     * conséquence pour un simple sélecteur (les enfants restent choisissables
     * individuellement s'ils sont eux-mêmes gérables).
     */
    @Transactional(readOnly = true)
    public List<DossierArbreDto> getArbreDossiers(Long uoId, User currentUser)
    {
        Set<Long> uoVisibles = uniteOrganisationnelleService.getUoIdsVisiblesPourLecture(currentUser);
        if (uoVisibles != null && !uoVisibles.contains(uoId))
        {
            return List.of();
        }

        return dossierRepository.findByUniteOrganisationnelleId(uoId).stream()
            .filter(d -> peutGererDossier(d, currentUser))
            .map(d -> DossierArbreDto.builder()
                .id(d.getId())
                .nom(d.getNom())
                .parentId(d.getParent() != null ? d.getParent().getId() : null)
                .build())
            .toList();
    }

    /**
     * Détail d'un dossier + checklist des types de documents attendus
     * (combien de documents de chaque type sont déjà rattachés — purement
     * informatif, voir Dossier.typesDocumentsAttendus).
     */
    @Transactional(readOnly = true)
    public DossierDetailDto getDossierDetail(Long dossierId, User currentUser)
    {
        Dossier dossier = dossierRepository.findById(dossierId)
            .orElseThrow(() -> new BusinessException("Dossier introuvable : " + dossierId));

        Set<Long> uoVisibles = uniteOrganisationnelleService.getUoIdsVisiblesPourLecture(currentUser);
        if (!estVisiblePour(dossier, currentUser, uoVisibles))
        {
            throw new AccessDeniedException("Vous n'avez pas accès à ce dossier");
        }

        Map<Long, Long> comptesParType = new HashMap<>();
        for (Object[] ligne : documentRepository.countDocumentsByTypeForDossier(dossierId))
        {
            comptesParType.put((Long) ligne[0], (Long) ligne[1]);
        }

        List<DossierDetailDto.TypeAttenduDto> typesAttendus = dossier.getTypesDocumentsAttendus() == null
            ? List.of()
            : dossier.getTypesDocumentsAttendus().stream()
                .map(t -> {
                    long nombre = comptesParType.getOrDefault(t.getId(), 0L);
                    return DossierDetailDto.TypeAttenduDto.builder()
                        .typeDocumentId(t.getId())
                        .nom(t.getNom())
                        .nombreDocuments(nombre)
                        .fourni(nombre > 0)
                        .build();
                })
                .toList();

        return DossierDetailDto.builder()
            .id(dossier.getId())
            .nom(dossier.getNom())
            .uoId(dossier.getUniteOrganisationnelle().getId())
            .uoNom(dossier.getUniteOrganisationnelle().getNom())
            .parentId(dossier.getParent() != null ? dossier.getParent().getId() : null)
            .parentNom(dossier.getParent() != null ? dossier.getParent().getNom() : null)
            .cheminComplet(construireChemin(dossier))
            .creePar(dossier.getCreePar().getPrenom() + " " + dossier.getCreePar().getNom())
            .createAt(dossier.getCreateAt())
            .typesAttendus(typesAttendus)
            .access(dossier.getAccess().name())
            .peutGererTypes(peutGererDossier(dossier, currentUser))
            .peutGererAcces(dossier.getAccess() == TypeAccess.PRIVE && peutGererGroupeDossier(dossier.getGroupe(), currentUser.getId()))
            .peutModifierAcces(peutGererDossier(dossier, currentUser))
            .verrouille(brancheAvecDocuments(dossier))
            .build();
    }

    /**
     * Fil d'Ariane complet du dossier, ex. "Contrats / 2026" — racine en
     * premier. Public : réutilisé par DocumentService pour
     * DocumentDetailDto.dossierCheminComplet (voir Javadoc), même principe
     * que PhysicalLocationService.construireChemin pour l'emplacement physique.
     */
    public String construireChemin(Dossier dossier)
    {
        java.util.Deque<String> segments = new java.util.ArrayDeque<>();
        Dossier courant = dossier;
        while (courant != null)
        {
            segments.addFirst(courant.getNom());
            courant = courant.getParent();
        }
        return String.join(" / ", segments);
    }

    /**
     * UO scope (null = ADMIN, pas de restriction) ET confidentialité : un
     * dossier PRIVÉ n'est visible qu'à ses membres — aucune exception de rôle,
     * même règle que pour les documents privés (décision explicite : pas de
     * contournement admin).
     */
    private boolean estVisiblePour(Dossier dossier, User user, Set<Long> uoVisibles)
    {
        if (uoVisibles != null && !uoVisibles.contains(dossier.getUniteOrganisationnelle().getId()))
        {
            return false;
        }
        if (dossier.getAccess() == TypeAccess.PRIVE)
        {
            return dossier.getGroupe() != null && dossier.getGroupe().getMembres().stream()
                .anyMatch(m -> m.getId().equals(user.getId()));
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Types de documents attendus — ajout / retrait (voir verifierPeutGererDossier :
    // tout éditeur de l'UO si le dossier est public, membre-éditeur du groupe
    // s'il est privé)
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public Dossier ajouterTypesAttendus(Long dossierId, List<Long> typeDocumentIds, User acteur)
    {
        Dossier dossier = dossierRepository.findById(dossierId)
            .orElseThrow(() -> new BusinessException("Dossier introuvable : " + dossierId));

        verifierPeutGererDossier(dossier, acteur);

        List<TypeDocument> nouveaux = resoudreTypesAttendus(typeDocumentIds, dossier.getUniteOrganisationnelle());

        Set<Long> dejaPresents = new LinkedHashSet<>();
        List<TypeDocument> existants = dossier.getTypesDocumentsAttendus();
        if (existants != null)
        {
            existants.forEach(t -> dejaPresents.add(t.getId()));
        }
        else
        {
            existants = new ArrayList<>();
        }

        for (TypeDocument t : nouveaux)
        {
            if (dejaPresents.add(t.getId()))
            {
                existants.add(t);
            }
        }

        dossier.setTypesDocumentsAttendus(existants);
        Dossier saved = dossierRepository.save(dossier);

        auditLogService.log(acteur, AuditAction.DOSSIER_TYPES_AJOUTES, AuditCible.DOSSIER,
            dossierId.toString(), dossier.getUniteOrganisationnelle().getId(),
            nouveaux.size() + " type(s) de document ajouté(s) au dossier " + dossier.getNom(), true);

        return saved;
    }

    /**
     * Retire un type de document attendu d'un dossier — refusé si des
     * documents de CE type existent DANS CE DOSSIER précis (peu importe le
     * reste de l'UO : un même type peut très bien avoir des documents dans
     * d'autres dossiers ou hors dossier, ça ne bloque pas ce retrait-ci).
     */
    @Transactional
    public Dossier retirerTypeAttendu(Long dossierId, Long typeDocumentId, User acteur)
    {
        Dossier dossier = dossierRepository.findById(dossierId)
            .orElseThrow(() -> new BusinessException("Dossier introuvable : " + dossierId));

        verifierPeutGererDossier(dossier, acteur);

        List<TypeDocument> existants = dossier.getTypesDocumentsAttendus();
        boolean present = existants != null && existants.stream().anyMatch(t -> t.getId().equals(typeDocumentId));
        if (!present)
        {
            throw new BusinessException("Ce type de document n'est pas attendu dans ce dossier");
        }

        Map<Long, Long> comptesParType = new HashMap<>();
        for (Object[] ligne : documentRepository.countDocumentsByTypeForDossier(dossierId))
        {
            comptesParType.put((Long) ligne[0], (Long) ligne[1]);
        }
        long nombre = comptesParType.getOrDefault(typeDocumentId, 0L);
        if (nombre > 0)
        {
            throw new BusinessException(
                "Impossible de retirer ce type : " + nombre
                    + " document(s) de ce type existent déjà dans ce dossier");
        }

        existants.removeIf(t -> t.getId().equals(typeDocumentId));
        dossier.setTypesDocumentsAttendus(existants);
        Dossier saved = dossierRepository.save(dossier);

        auditLogService.log(acteur, AuditAction.DOSSIER_TYPE_RETIRE, AuditCible.DOSSIER,
            dossierId.toString(), dossier.getUniteOrganisationnelle().getId(),
            "Type de document retiré du dossier " + dossier.getNom(), true);

        return saved;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Suppression — voir verifierPeutGererDossier, uniquement si le dossier est
    // vide (aucun document rattaché)
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public void supprimerDossier(Long dossierId, User acteur)
    {
        Dossier dossier = dossierRepository.findById(dossierId)
            .orElseThrow(() -> new BusinessException("Dossier introuvable : " + dossierId));

        verifierPeutGererDossier(dossier, acteur);

        // Le dossier part AVEC toute sa sous-arborescence — à condition qu'aucun document n'y soit classé (nulle part
        // dans la branche) et qu'aucun emplacement physique n'attende un de ces dossiers.
        List<Dossier> branche = brancheDe(dossier);
        Set<Long> ids = branche.stream().map(Dossier::getId).collect(java.util.stream.Collectors.toSet());

        if (documentRepository.existsByDossierIdIn(ids))
        {
            throw new BusinessException("Impossible de supprimer ce dossier : des documents sont classés dedans"
                + (ids.size() > 1 ? " ou dans l'un de ses sous-dossiers" : ""));
        }
        if (physicalLocationRepository.existsByDossierIdIn(ids))
        {
            throw new BusinessException("Impossible de supprimer ce dossier : des emplacements physiques "
                + "n'acceptent que ce dossier (ou l'un de ses sous-dossiers)");
        }

        Long uoId = dossier.getUniteOrganisationnelle().getId();
        String nom = dossier.getNom();

        // Les feuilles d'abord : la clé étrangère parent_id interdit de supprimer un parent encore référencé.
        // Le groupe d'accès de chaque dossier devient orphelin (aucun document ne peut le référencer, voir ci-dessus).
        for (Dossier d : branche)
        {
            GroupeAccess groupe = d.getGroupe();
            dossierRepository.delete(d);
            dossierRepository.flush();
            if (groupe != null)
            {
                groupeAccessRepository.delete(groupe);
            }
        }

        log.info("[Dossier] '{}' supprimé avec {} sous-dossier(s) (UO {}) par {}", nom, ids.size() - 1, uoId, acteur.getEmail());

        auditLogService.log(acteur, AuditAction.DOSSIER_SUPPRIME, AuditCible.DOSSIER,
            dossierId.toString(), uoId, "Suppression du dossier " + nom
                + (ids.size() > 1 ? " avec ses " + (ids.size() - 1) + " sous-dossier(s)" : ""), true);
    }

    /** Le dossier et tous ses descendants, FEUILLES D'ABORD (ordre sûr pour une suppression). */
    private List<Dossier> brancheDe(Dossier racine)
    {
        Map<Long, List<Dossier>> enfants = new HashMap<>();
        for (Dossier d : dossierRepository.findByUniteOrganisationnelleId(racine.getUniteOrganisationnelle().getId()))
        {
            if (d.getParent() != null)
            {
                enfants.computeIfAbsent(d.getParent().getId(), k -> new java.util.ArrayList<>()).add(d);
            }
        }
        List<Dossier> ordre = new java.util.ArrayList<>();
        java.util.ArrayDeque<Dossier> pile = new java.util.ArrayDeque<>();
        pile.push(racine);
        Set<Long> vus = new java.util.HashSet<>();
        while (!pile.isEmpty())
        {
            Dossier d = pile.pop();
            if (!vus.add(d.getId())) continue;
            ordre.add(d);
            enfants.getOrDefault(d.getId(), List.of()).forEach(pile::push);
        }
        java.util.Collections.reverse(ordre);   // parents d'abord → feuilles d'abord
        return ordre;
    }

    /** Refuse de renommer/déplacer un dossier dont la branche (lui ou un descendant) contient des documents. */
    private void verifierBrancheSansDocument(Dossier dossier, String action)
    {
        Set<Long> ids = brancheDe(dossier).stream().map(Dossier::getId).collect(java.util.stream.Collectors.toSet());
        if (documentRepository.existsByDossierIdIn(ids))
        {
            throw new BusinessException("Impossible de " + action + " ce dossier : des documents sont classés dedans"
                + (ids.size() > 1 ? " ou dans l'un de ses sous-dossiers" : ""));
        }
    }

    /** true si ce dossier ou l'un de ses descendants contient des documents. */
    private boolean brancheAvecDocuments(Dossier dossier)
    {
        return documentRepository.existsByDossierIdIn(
            brancheDe(dossier).stream().map(Dossier::getId).collect(java.util.stream.Collectors.toSet()));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Confidentialité — gestion du GroupeAccess du dossier, réservée à un
    // membre du groupe ayant AUSSI le rôle éditeur (voir peutGererGroupeDossier) —
    // pas au seul créateur : un groupe figé si le créateur quitte l'UO serait
    // ingérable. Même modèle que GroupeAccessService pour un document.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Liste les membres du groupe — ouvert à N'IMPORTE QUEL membre, comme pour
     * un document : tout membre a le droit de savoir qui d'autre a accès.
     * peutGerer indique si CE demandeur est membre ET éditeur, seul cas
     * habilité à ajouter/retirer des membres.
     */
    @Transactional(readOnly = true)
    public GroupeMembresDto getMembresDossier(Long dossierId, UUID demandeurId)
    {
        Dossier dossier = getDossierPrive(dossierId);
        verifierMembreDossier(dossier.getGroupe(), demandeurId);

        return GroupeMembresDto.builder()
            .membres(dossier.getGroupe().getMembres())
            .uploadeurId(dossier.getCreePar().getId())
            .peutGerer(peutGererGroupeDossier(dossier.getGroupe(), demandeurId))
            .build();
    }

    /**
     * Utilisateurs proposables comme membres : les collègues de la propre UO
     * du dossier, plus tous les ADMIN globaux — jamais l'annuaire complet de
     * la plateforme. Règle déléguée à
     * UniteOrganisationnelleService.getCandidatsGroupeAcces, PARTAGÉE avec
     * GroupeAccessService.getUtilisateursDisponibles (même règle, un document
     * plutôt qu'un dossier) et UserService.getCandidatsGroupeAccesPourUO
     * (même règle, À LA CRÉATION plutôt qu'après coup).
     */
    @Transactional(readOnly = true)
    public List<User> getUtilisateursDisponiblesDossier(Long dossierId, UUID demandeurId)
    {
        Dossier dossier = getDossierPrive(dossierId);
        verifierPeutGererGroupeDossier(dossier.getGroupe(), demandeurId);

        User demandeur = userRepository.findById(demandeurId)
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable : " + demandeurId));

        List<UUID> membresIds = dossier.getGroupe().getMembres().stream()
            .map(User::getId)
            .toList();

        List<User> candidats = uniteOrganisationnelleService.getCandidatsGroupeAcces(
            dossier.getUniteOrganisationnelle().getId(), demandeur);

        return candidats.stream()
            .filter(u -> !membresIds.contains(u.getId()))
            .toList();
    }

    @Transactional
    public void ajouterMembreDossier(Long dossierId, UUID demandeurId, UUID nouveauMembreId)
    {
        Dossier dossier = getDossierPrive(dossierId);
        GroupeAccess groupe = dossier.getGroupe();
        verifierPeutGererGroupeDossier(groupe, demandeurId);

        User nouveauMembre = userRepository.findById(nouveauMembreId)
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable : " + nouveauMembreId));

        boolean dejaPresent = groupe.getMembres().stream()
            .anyMatch(m -> m.getId().equals(nouveauMembreId));
        if (dejaPresent)
        {
            throw new BusinessException("Cet utilisateur est déjà membre du groupe");
        }

        groupe.getMembres().add(nouveauMembre);
        groupeAccessRepository.save(groupe);

        auditLogService.log(userRepository.findById(demandeurId).orElse(null),
            AuditAction.GROUPE_MEMBRE_AJOUTE, AuditCible.DOSSIER, dossierId.toString(),
            dossier.getUniteOrganisationnelle().getId(),
            nouveauMembre.getEmail() + " ajouté au groupe d'accès du dossier \"" + dossier.getNom() + "\"",
            true);

        // Cohérence de l'invariant (voir Javadoc de Dossier.parent) :
        // quiconque peut voir ce sous-dossier doit pouvoir naviguer jusqu'à
        // lui en passant par ses parents — propage vers le haut sur toute la
        // chaîne d'ancêtres PRIVÉS.
        propagerMembreVersAncetres(dossier.getParent(), nouveauMembre, demandeurId);
    }

    /**
     * Ajoute nouveauMembre au groupe de chaque ancêtre PRIVÉ, en remontant
     * depuis "dossier" jusqu'à la racine ou jusqu'au premier ancêtre PUBLIC
     * (au-delà duquel tout le monde a déjà accès — la propagation n'a plus de
     * sens, on s'arrête net). Silencieux si le membre est déjà présent à un
     * niveau donné (rien à faire à ce niveau, la remontée continue).
     */
    private void propagerMembreVersAncetres(Dossier dossier, User nouveauMembre, UUID demandeurId)
    {
        if (dossier == null || dossier.getAccess() != TypeAccess.PRIVE || dossier.getGroupe() == null)
        {
            return;
        }

        GroupeAccess groupe = dossier.getGroupe();
        boolean dejaPresent = groupe.getMembres().stream()
            .anyMatch(m -> m.getId().equals(nouveauMembre.getId()));
        if (!dejaPresent)
        {
            groupe.getMembres().add(nouveauMembre);
            groupeAccessRepository.save(groupe);

            auditLogService.log(userRepository.findById(demandeurId).orElse(null),
                AuditAction.GROUPE_MEMBRE_AJOUTE, AuditCible.DOSSIER, dossier.getId().toString(),
                dossier.getUniteOrganisationnelle().getId(),
                nouveauMembre.getEmail() + " ajouté automatiquement au groupe du dossier \"" + dossier.getNom()
                    + "\" (cohérence avec un sous-dossier)", true);
        }

        propagerMembreVersAncetres(dossier.getParent(), nouveauMembre, demandeurId);
    }

    /**
     * Le créateur ne peut jamais être retiré de son propre groupe — il en
     * reste toujours membre, même garde que pour un document.
     */
    @Transactional
    public void retirerMembreDossier(Long dossierId, UUID demandeurId, UUID membreARetirerID)
    {
        Dossier dossier = getDossierPrive(dossierId);
        GroupeAccess groupe = dossier.getGroupe();
        verifierPeutGererGroupeDossier(groupe, demandeurId);

        if (membreARetirerID.equals(dossier.getCreePar().getId()))
        {
            throw new BusinessException(
                "Le créateur de ce dossier ne peut pas être retiré de son groupe d'accès");
        }

        boolean estMembre = groupe.getMembres().stream()
            .anyMatch(m -> m.getId().equals(membreARetirerID));
        if (!estMembre)
        {
            throw new BusinessException("Cet utilisateur n'est pas membre du groupe");
        }

        String email = userRepository.findById(membreARetirerID)
            .map(User::getEmail).orElse(membreARetirerID.toString());

        groupe.getMembres().removeIf(m -> m.getId().equals(membreARetirerID));
        groupeAccessRepository.save(groupe);

        auditLogService.log(userRepository.findById(demandeurId).orElse(null),
            AuditAction.GROUPE_MEMBRE_RETIRE, AuditCible.DOSSIER, dossierId.toString(),
            dossier.getUniteOrganisationnelle().getId(),
            email + " retiré du groupe d'accès du dossier \"" + dossier.getNom() + "\"",
            true);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Méthodes privées
    // ═══════════════════════════════════════════════════════════════════

    private Dossier getDossierPrive(Long dossierId)
    {
        Dossier dossier = dossierRepository.findById(dossierId)
            .orElseThrow(() -> new BusinessException("Dossier introuvable : " + dossierId));

        if (dossier.getAccess() != TypeAccess.PRIVE || dossier.getGroupe() == null)
        {
            throw new BusinessException("Ce dossier n'est pas un dossier privé");
        }

        return dossier;
    }

    private void verifierMembreDossier(GroupeAccess groupe, UUID userId)
    {
        boolean estMembre = groupe.getMembres().stream()
            .anyMatch(m -> m.getId().equals(userId));
        if (!estMembre)
        {
            throw new BusinessException("Accès refusé : vous n'êtes pas membre de ce groupe");
        }
    }

    /**
     * Gestion du groupe d'un dossier (ajout/retrait de membres) réservée à un
     * membre ayant AUSSI le rôle éditeur — pas au seul créateur, pour éviter
     * qu'un groupe se retrouve figé si le créateur quitte l'UO (les autres
     * membres-éditeurs prennent le relais). Même logique que
     * GroupeAccessService.peutGererGroupe pour un document.
     */
    private boolean peutGererGroupeDossier(GroupeAccess groupe, UUID userId)
    {
        if (groupe == null)
        {
            return false;
        }
        boolean estMembre = groupe.getMembres().stream().anyMatch(m -> m.getId().equals(userId));
        if (!estMembre)
        {
            return false;
        }
        return userRepository.findById(userId)
            .map(u -> u.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.EDITOR))
            .orElse(false);
    }

    private void verifierPeutGererGroupeDossier(GroupeAccess groupe, UUID demandeurId)
    {
        if (!peutGererGroupeDossier(groupe, demandeurId))
        {
            throw new AccessDeniedException(
                "Seul un membre de ce groupe ayant le rôle éditeur peut le gérer");
        }
    }

    /**
     * Résout et valide les types de documents attendus déclarés : ils doivent
     * appartenir à la MÊME UO que le dossier (un type d'une autre UO n'aurait
     * pas de sens ici).
     */
    private List<TypeDocument> resoudreTypesAttendus(List<Long> typeDocumentIds, UniteOrganisationnelle uo)
    {
        if (typeDocumentIds == null || typeDocumentIds.isEmpty())
        {
            return List.of();
        }

        List<TypeDocument> types = typeDocumentRepository.findAllById(typeDocumentIds);

        if (types.size() != typeDocumentIds.size())
        {
            throw new BusinessException("Un ou plusieurs types de documents sont introuvables");
        }

        boolean touslDeCetteUO = types.stream()
            .allMatch(t -> t.getUniteOrganisationnelle() != null
                && t.getUniteOrganisationnelle().getId().equals(uo.getId()));

        if (!touslDeCetteUO)
        {
            throw new BusinessException(
                "Les types de documents attendus doivent appartenir à la même unité organisationnelle que le dossier");
        }

        return types;
    }

    /**
     * Doublon détecté après NORMALISATION (casse, accents, espacement
     * intégralement retiré — voir NormalisationNoms) : "Archivage 2024" et
     * "archivage2024" sont désormais LE MÊME nom, pas une simple comparaison
     * IgnoreCase SQL. Portée : entre FRÈRES/SŒURS — même parent (ou même
     * niveau racine si parent==null), dans la même UO (revu le 09/2026,
     * depuis l'ajout des sous-dossiers — auparavant toute l'UO à plat).
     * exclutId : le dossier qu'on est justement en train de renommer (null à
     * la création).
     */
    private void verifierNomUnique(String nom, Long uoId, Dossier parent, Long exclutId)
    {
        List<Dossier> fratrie = parent == null
            ? dossierRepository.findByParentIsNullAndUniteOrganisationnelleId(uoId)
            : dossierRepository.findByParentId(parent.getId());

        String nomNormalise = NormalisationNoms.normaliser(nom);
        boolean conflit = fratrie.stream()
            .anyMatch(d -> NormalisationNoms.normaliser(d.getNom()).equals(nomNormalise)
                && (exclutId == null || !d.getId().equals(exclutId)));

        if (conflit)
        {
            throw new BusinessException("Un dossier avec ce nom existe déjà dans cette UO");
        }
    }

    /**
     * Seul un EDITOR de la PROPRE UO concernée peut créer un dossier — ni
     * ADMIN_UO ni ADMIN (droit de lecture seulement, voir Javadoc de la
     * classe), ni un EDITOR d'une autre UO. Utilisé uniquement à la création
     * (aucun dossier n'existe encore pour tester une éventuelle appartenance
     * au groupe) — voir peutGererDossier pour les actions sur un dossier
     * existant (types attendus, suppression).
     */
    private boolean estEditeurDeUO(Long uoId, User acteur)
    {
        boolean estEditeur = acteur.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.EDITOR);
        if (!estEditeur)
        {
            return false;
        }
        return uniteOrganisationnelleService.getUOActuelleUser(acteur.getId())
            .map(dto -> uoId.equals(dto.getId()))
            .orElse(false);
    }

    private void verifierEstEditeurDeUO(Long uoId, User acteur)
    {
        if (!estEditeurDeUO(uoId, acteur))
        {
            throw new AccessDeniedException(
                "Seul un éditeur de cette unité organisationnelle peut effectuer cette action");
        }
    }

    /**
     * Autorité d'action sur un dossier EXISTANT (types attendus, suppression) :
     *   - PUBLIC  : tout éditeur de la propre UO du dossier (comme à la création).
     *   - PRIVÉ   : éditeur ET membre du groupe d'accès — un éditeur de l'UO qui
     *     n'est pas membre ne doit rien pouvoir faire sur un dossier privé,
     *     sans quoi la confidentialité serait contournable en devinant l'id.
     */
    private boolean peutGererDossier(Dossier dossier, User acteur)
    {
        boolean estEditeur = acteur.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.EDITOR);
        if (!estEditeur)
        {
            return false;
        }

        if (dossier.getAccess() == TypeAccess.PRIVE)
        {
            return peutGererGroupeDossier(dossier.getGroupe(), acteur.getId());
        }

        return uniteOrganisationnelleService.getUOActuelleUser(acteur.getId())
            .map(dto -> dossier.getUniteOrganisationnelle().getId().equals(dto.getId()))
            .orElse(false);
    }

    private void verifierPeutGererDossier(Dossier dossier, User acteur)
    {
        if (!peutGererDossier(dossier, acteur))
        {
            throw new AccessDeniedException(
                dossier.getAccess() == TypeAccess.PRIVE
                    ? "Ce dossier est privé — seul un éditeur membre de son groupe d'accès peut effectuer cette action"
                    : "Seul un éditeur de cette unité organisationnelle peut effectuer cette action");
        }
    }

    /**
     * Vérifie que l'acteur a l'autorité d'ARCHIVER un document dans ce
     * dossier — même porte que peutGererDossier (créer un sous-dossier,
     * déclarer un type attendu...). Appelée par DocumentUploadeService au
     * moment même de l'archivage : le sélecteur de dossier filtré côté liste
     * (voir getArbreDossiers) ne suffit pas seul à protéger ce point — un
     * appel direct à l'API avec un dossierId arbitraire ne doit pas pouvoir
     * contourner cette autorité.
     */
    public void verifierPeutArchiverDans(Dossier dossier, User acteur)
    {
        verifierPeutGererDossier(dossier, acteur);
    }
}
