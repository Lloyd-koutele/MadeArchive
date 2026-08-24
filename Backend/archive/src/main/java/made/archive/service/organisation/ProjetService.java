package made.archive.service.organisation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.GroupeMembresDto;
import made.archive.dto.ProjetDetailDto;
import made.archive.dto.ProjetDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.GroupeAccess;
import made.archive.entite.Projet;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeAccess;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.GroupeAccessRepository;
import made.archive.repository.ProjetRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static made.archive.entite.NotificationType.PROJET_CREE;

/**
 * Un projet est un conteneur qui regroupe des documents (dossier/affaire).
 * Voir made.archive.entite.Projet.
 *
 * Modèle de propriété : le PROJET est entièrement piloté par des EDITOR — sa
 * création, exclusivement par le créateur au départ. Pour un projet PUBLIC,
 * n'importe quel éditeur de sa propre UO peut ensuite agir (types attendus,
 * suppression si vide) — voir peutGererProjet. Pour un projet PRIVÉ, il faut
 * en plus être membre de son GroupeAccess : un éditeur de l'UO qui n'est pas
 * membre n'a aucun droit dessus, ni même de le lister/consulter (voir
 * estVisiblePour). Le créateur reste protégé (jamais retirable de son propre
 * groupe) mais n'est plus seul à pouvoir gérer — ça évite qu'un projet privé
 * se retrouve figé si son créateur quitte l'UO : les autres membres-éditeurs
 * prennent le relais (voir peutGererGroupeProjet).
 *
 * ADMIN et ADMIN_UO n'ont eux aucun droit d'écriture sur les projets,
 * uniquement un droit de lecture, lui-même soumis aux mêmes règles de
 * confidentialité que tout le monde — aucun contournement de rôle, y compris
 * dans les listes/recherches, pour éviter toute fuite d'un projet privé.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjetService
{
    private final ProjetRepository                projetRepository;
    private final UniteOrganisationnelleRepository uoRepository;
    private final TypeDocumentRepository           typeDocumentRepository;
    private final DocumentRepository               documentRepository;
    private final UserRepository                   userRepository;
    private final GroupeAccessRepository           groupeAccessRepository;
    private final UniteOrganisationnelleService    uniteOrganisationnelleService;
    private final NotificationService              notificationService;
    private final AuditLogService                  auditLogService;

    // ═══════════════════════════════════════════════════════════════════
    // Création
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public Projet creerProjet(ProjetDto dto, User createur)
    {
        if (dto.getNom() == null || dto.getNom().isBlank())
        {
            throw new BusinessException("Le nom du projet est obligatoire");
        }
        if (dto.getUoId() == null)
        {
            throw new BusinessException("L'unité organisationnelle est obligatoire");
        }

        UniteOrganisationnelle uo = uoRepository.findById(dto.getUoId())
            .orElseThrow(() -> new BusinessException(
                "Unité organisationnelle introuvable : " + dto.getUoId()));

        verifierEstEditeurDeUO(uo.getId(), createur);

        if (projetRepository.existsByNomIgnoreCaseAndUniteOrganisationnelleId(dto.getNom(), uo.getId()))
        {
            throw new BusinessException("Un projet avec ce nom existe déjà dans cette UO");
        }

        List<TypeDocument> typesAttendus = resoudreTypesAttendus(dto.getTypeDocumentIds(), uo);

        TypeAccess access = "PRIVE".equalsIgnoreCase(dto.getAccess()) ? TypeAccess.PRIVE : TypeAccess.PUBLIC;

        // Groupe d'accès créé UNE SEULE FOIS ici si le projet est privé — tout
        // document versé dedans réutilisera CE MÊME groupe (voir
        // DocumentUploadeService), jamais un groupe recréé par document.
        GroupeAccess groupe = null;
        if (access == TypeAccess.PRIVE)
        {
            GroupeAccess g = new GroupeAccess();
            g.setNom("Accès — " + dto.getNom());
            g.setCreateAt(LocalDate.now());

            List<User> membres = new ArrayList<>();
            membres.add(createur);
            if (dto.getGroupeMembresIds() != null && !dto.getGroupeMembresIds().isEmpty())
            {
                List<User> autres = userRepository.findAllById(
                    dto.getGroupeMembresIds().stream()
                        .filter(id -> !id.equals(createur.getId()))
                        .toList());
                membres.addAll(autres);
            }
            g.setMembres(membres);
            groupe = groupeAccessRepository.save(g);
        }

        Projet projet = new Projet();
        projet.setNom(dto.getNom());
        projet.setDescription(dto.getDescription());
        projet.setUniteOrganisationnelle(uo);
        projet.setCreePar(createur);
        projet.setCreateAt(LocalDateTime.now());
        projet.setTypesDocumentsAttendus(typesAttendus);
        projet.setAccess(access);
        projet.setGroupe(groupe);

        Projet saved = projetRepository.save(projet);
        log.info("[Projet] '{}' créé dans l'UO {} par {} ({}, {} type(s) attendu(s))",
            saved.getNom(), uo.getId(), createur.getEmail(), access, typesAttendus.size());

        auditLogService.log(createur, AuditAction.PROJET_CREE, AuditCible.PROJET,
            saved.getId().toString(), uo.getId(),
            "Création du projet " + saved.getNom() + " dans l'UO " + uo.getNom(), true);

        notifierCreationProjet(saved, uo, createur, access, groupe);

        return saved;
    }

    private void notifierCreationProjet(
        Projet projet, UniteOrganisationnelle uo, User createur, TypeAccess access, GroupeAccess groupe)
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
                // Projet PRIVÉ : ne notifier QUE les membres du groupe — notifier
                // toute l'UO ferait fuiter l'existence et le nom du projet à des
                // personnes qui n'y ont justement pas accès.
                destinataires.addAll(groupe.getMembres());
            }

            notificationService.notifier(destinataires, PROJET_CREE,
                "Un nouveau projet \"" + projet.getNom() + "\" a été créé dans l'unité organisationnelle \""
                    + uo.getNom() + "\" par " + createur.getPrenom() + " " + createur.getNom() + ".");
        }
        catch (Exception e)
        {
            log.warn("[Projet] Notification (best-effort) échouée pour le projet {} : {}",
                projet.getId(), e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lecture — scopée par UO ET par confidentialité, jamais de contournement
    // de rôle (voir Javadoc de la classe)
    // ═══════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Projet> getProjetsDeUO(Long uoId, User currentUser)
    {
        Set<Long> uoVisibles = uniteOrganisationnelleService.getUoIdsVisiblesPourLecture(currentUser);
        if (uoVisibles != null && !uoVisibles.contains(uoId))
        {
            // Hors périmètre : liste vide plutôt qu'une exception — ne confirme
            // ni n'infirme l'existence de l'UO à quelqu'un qui n'y a pas accès.
            return List.of();
        }

        return projetRepository.findByUniteOrganisationnelleId(uoId).stream()
            .filter(p -> estVisiblePour(p, currentUser, uoVisibles))
            .toList();
    }

    /**
     * Détail d'un projet + checklist des types de documents attendus
     * (combien de documents de chaque type sont déjà rattachés — purement
     * informatif, voir Projet.typesDocumentsAttendus).
     */
    @Transactional(readOnly = true)
    public ProjetDetailDto getProjetDetail(Long projetId, User currentUser)
    {
        Projet projet = projetRepository.findById(projetId)
            .orElseThrow(() -> new BusinessException("Projet introuvable : " + projetId));

        Set<Long> uoVisibles = uniteOrganisationnelleService.getUoIdsVisiblesPourLecture(currentUser);
        if (!estVisiblePour(projet, currentUser, uoVisibles))
        {
            throw new AccessDeniedException("Vous n'avez pas accès à ce projet");
        }

        Map<Long, Long> comptesParType = new HashMap<>();
        for (Object[] ligne : documentRepository.countDocumentsByTypeForProjet(projetId))
        {
            comptesParType.put((Long) ligne[0], (Long) ligne[1]);
        }

        List<ProjetDetailDto.TypeAttenduDto> typesAttendus = projet.getTypesDocumentsAttendus() == null
            ? List.of()
            : projet.getTypesDocumentsAttendus().stream()
                .map(t -> {
                    long nombre = comptesParType.getOrDefault(t.getId(), 0L);
                    return ProjetDetailDto.TypeAttenduDto.builder()
                        .typeDocumentId(t.getId())
                        .nom(t.getNom())
                        .nombreDocuments(nombre)
                        .fourni(nombre > 0)
                        .build();
                })
                .toList();

        return ProjetDetailDto.builder()
            .id(projet.getId())
            .nom(projet.getNom())
            .description(projet.getDescription())
            .uoId(projet.getUniteOrganisationnelle().getId())
            .uoNom(projet.getUniteOrganisationnelle().getNom())
            .creePar(projet.getCreePar().getPrenom() + " " + projet.getCreePar().getNom())
            .createAt(projet.getCreateAt())
            .typesAttendus(typesAttendus)
            .access(projet.getAccess().name())
            .peutGererTypes(peutGererProjet(projet, currentUser))
            .peutGererAcces(projet.getAccess() == TypeAccess.PRIVE && peutGererGroupeProjet(projet.getGroupe(), currentUser.getId()))
            .build();
    }

    /**
     * UO scope (null = ADMIN, pas de restriction) ET confidentialité : un
     * projet PRIVÉ n'est visible qu'à ses membres — aucune exception de rôle,
     * même règle que pour les documents privés (décision explicite : pas de
     * contournement admin).
     */
    private boolean estVisiblePour(Projet projet, User user, Set<Long> uoVisibles)
    {
        if (uoVisibles != null && !uoVisibles.contains(projet.getUniteOrganisationnelle().getId()))
        {
            return false;
        }
        if (projet.getAccess() == TypeAccess.PRIVE)
        {
            return projet.getGroupe() != null && projet.getGroupe().getMembres().stream()
                .anyMatch(m -> m.getId().equals(user.getId()));
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Types de documents attendus — ajout / retrait (voir verifierPeutGererProjet :
    // tout éditeur de l'UO si le projet est public, membre-éditeur du groupe
    // s'il est privé)
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public Projet ajouterTypesAttendus(Long projetId, List<Long> typeDocumentIds, User acteur)
    {
        Projet projet = projetRepository.findById(projetId)
            .orElseThrow(() -> new BusinessException("Projet introuvable : " + projetId));

        verifierPeutGererProjet(projet, acteur);

        List<TypeDocument> nouveaux = resoudreTypesAttendus(typeDocumentIds, projet.getUniteOrganisationnelle());

        Set<Long> dejaPresents = new LinkedHashSet<>();
        List<TypeDocument> existants = projet.getTypesDocumentsAttendus();
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

        projet.setTypesDocumentsAttendus(existants);
        Projet saved = projetRepository.save(projet);

        auditLogService.log(acteur, AuditAction.PROJET_TYPES_AJOUTES, AuditCible.PROJET,
            projetId.toString(), projet.getUniteOrganisationnelle().getId(),
            nouveaux.size() + " type(s) de document ajouté(s) au projet " + projet.getNom(), true);

        return saved;
    }

    /**
     * Retire un type de document attendu d'un projet — refusé si des
     * documents de CE type existent DANS CE PROJET précis (peu importe le
     * reste de l'UO : un même type peut très bien avoir des documents dans
     * d'autres projets ou hors projet, ça ne bloque pas ce retrait-ci).
     */
    @Transactional
    public Projet retirerTypeAttendu(Long projetId, Long typeDocumentId, User acteur)
    {
        Projet projet = projetRepository.findById(projetId)
            .orElseThrow(() -> new BusinessException("Projet introuvable : " + projetId));

        verifierPeutGererProjet(projet, acteur);

        List<TypeDocument> existants = projet.getTypesDocumentsAttendus();
        boolean present = existants != null && existants.stream().anyMatch(t -> t.getId().equals(typeDocumentId));
        if (!present)
        {
            throw new BusinessException("Ce type de document n'est pas attendu dans ce projet");
        }

        Map<Long, Long> comptesParType = new HashMap<>();
        for (Object[] ligne : documentRepository.countDocumentsByTypeForProjet(projetId))
        {
            comptesParType.put((Long) ligne[0], (Long) ligne[1]);
        }
        long nombre = comptesParType.getOrDefault(typeDocumentId, 0L);
        if (nombre > 0)
        {
            throw new BusinessException(
                "Impossible de retirer ce type : " + nombre
                    + " document(s) de ce type existent déjà dans ce projet");
        }

        existants.removeIf(t -> t.getId().equals(typeDocumentId));
        projet.setTypesDocumentsAttendus(existants);
        Projet saved = projetRepository.save(projet);

        auditLogService.log(acteur, AuditAction.PROJET_TYPE_RETIRE, AuditCible.PROJET,
            projetId.toString(), projet.getUniteOrganisationnelle().getId(),
            "Type de document retiré du projet " + projet.getNom(), true);

        return saved;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Suppression — voir verifierPeutGererProjet, uniquement si le projet est
    // vide (aucun document rattaché)
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public void supprimerProjet(Long projetId, User acteur)
    {
        Projet projet = projetRepository.findById(projetId)
            .orElseThrow(() -> new BusinessException("Projet introuvable : " + projetId));

        verifierPeutGererProjet(projet, acteur);

        if (documentRepository.existsByProjetId(projetId))
        {
            throw new BusinessException("Impossible de supprimer un projet contenant des documents");
        }

        Long uoId = projet.getUniteOrganisationnelle().getId();
        String nom = projet.getNom();
        GroupeAccess groupe = projet.getGroupe();

        projetRepository.delete(projet);

        // Le groupe d'accès du projet devient orphelin : aucun document ne peut
        // le référencer puisque le projet vient de refuser sa suppression s'il
        // en avait (voir ci-dessus) — le seul moyen pour un document de
        // partager ce groupe est justement d'appartenir à ce projet.
        if (groupe != null)
        {
            groupeAccessRepository.delete(groupe);
        }

        log.info("[Projet] '{}' supprimé (UO {}) par {}", nom, uoId, acteur.getEmail());

        auditLogService.log(acteur, AuditAction.PROJET_SUPPRIME, AuditCible.PROJET,
            projetId.toString(), uoId, "Suppression du projet " + nom, true);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Confidentialité — gestion du GroupeAccess du projet, réservée à un
    // membre du groupe ayant AUSSI le rôle éditeur (voir peutGererGroupeProjet) —
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
    public GroupeMembresDto getMembresProjet(Long projetId, UUID demandeurId)
    {
        Projet projet = getProjetPrive(projetId);
        verifierMembreProjet(projet.getGroupe(), demandeurId);

        return GroupeMembresDto.builder()
            .membres(projet.getGroupe().getMembres())
            .uploadeurId(projet.getCreePar().getId())
            .peutGerer(peutGererGroupeProjet(projet.getGroupe(), demandeurId))
            .build();
    }

    /**
     * Utilisateurs proposables comme membres : les collègues de la propre UO
     * du projet, plus tous les ADMIN globaux — jamais l'annuaire complet de
     * la plateforme (même règle que GroupeAccessService.getUtilisateursDisponibles).
     */
    @Transactional(readOnly = true)
    public List<User> getUtilisateursDisponiblesProjet(Long projetId, UUID demandeurId)
    {
        Projet projet = getProjetPrive(projetId);
        verifierPeutGererGroupeProjet(projet.getGroupe(), demandeurId);

        User demandeur = userRepository.findById(demandeurId)
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable : " + demandeurId));

        List<UUID> membresIds = projet.getGroupe().getMembres().stream()
            .map(User::getId)
            .toList();

        Map<UUID, User> candidats = new LinkedHashMap<>();

        List<User> collegues = uniteOrganisationnelleService.getUtilisateursDeUO(
            projet.getUniteOrganisationnelle().getId(), demandeur);
        collegues.forEach(u -> candidats.put(u.getId(), u));

        userRepository.findByRoleName(Role_Name.ADMIN)
            .forEach(u -> candidats.put(u.getId(), u));

        return candidats.values().stream()
            .filter(u -> !membresIds.contains(u.getId()))
            .toList();
    }

    @Transactional
    public void ajouterMembreProjet(Long projetId, UUID demandeurId, UUID nouveauMembreId)
    {
        Projet projet = getProjetPrive(projetId);
        GroupeAccess groupe = projet.getGroupe();
        verifierPeutGererGroupeProjet(groupe, demandeurId);

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
            AuditAction.GROUPE_MEMBRE_AJOUTE, AuditCible.PROJET, projetId.toString(),
            projet.getUniteOrganisationnelle().getId(),
            nouveauMembre.getEmail() + " ajouté au groupe d'accès du projet \"" + projet.getNom() + "\"",
            true);
    }

    /**
     * Le créateur ne peut jamais être retiré de son propre groupe — il en
     * reste toujours membre, même garde que pour un document.
     */
    @Transactional
    public void retirerMembreProjet(Long projetId, UUID demandeurId, UUID membreARetirerID)
    {
        Projet projet = getProjetPrive(projetId);
        GroupeAccess groupe = projet.getGroupe();
        verifierPeutGererGroupeProjet(groupe, demandeurId);

        if (membreARetirerID.equals(projet.getCreePar().getId()))
        {
            throw new BusinessException(
                "Le créateur de ce projet ne peut pas être retiré de son groupe d'accès");
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
            AuditAction.GROUPE_MEMBRE_RETIRE, AuditCible.PROJET, projetId.toString(),
            projet.getUniteOrganisationnelle().getId(),
            email + " retiré du groupe d'accès du projet \"" + projet.getNom() + "\"",
            true);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Méthodes privées
    // ═══════════════════════════════════════════════════════════════════

    private Projet getProjetPrive(Long projetId)
    {
        Projet projet = projetRepository.findById(projetId)
            .orElseThrow(() -> new BusinessException("Projet introuvable : " + projetId));

        if (projet.getAccess() != TypeAccess.PRIVE || projet.getGroupe() == null)
        {
            throw new BusinessException("Ce projet n'est pas un projet privé");
        }

        return projet;
    }

    private void verifierMembreProjet(GroupeAccess groupe, UUID userId)
    {
        boolean estMembre = groupe.getMembres().stream()
            .anyMatch(m -> m.getId().equals(userId));
        if (!estMembre)
        {
            throw new BusinessException("Accès refusé : vous n'êtes pas membre de ce groupe");
        }
    }

    /**
     * Gestion du groupe d'un projet (ajout/retrait de membres) réservée à un
     * membre ayant AUSSI le rôle éditeur — pas au seul créateur, pour éviter
     * qu'un groupe se retrouve figé si le créateur quitte l'UO (les autres
     * membres-éditeurs prennent le relais). Même logique que
     * GroupeAccessService.peutGererGroupe pour un document.
     */
    private boolean peutGererGroupeProjet(GroupeAccess groupe, UUID userId)
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

    private void verifierPeutGererGroupeProjet(GroupeAccess groupe, UUID demandeurId)
    {
        if (!peutGererGroupeProjet(groupe, demandeurId))
        {
            throw new AccessDeniedException(
                "Seul un membre de ce groupe ayant le rôle éditeur peut le gérer");
        }
    }

    /**
     * Résout et valide les types de documents attendus déclarés : ils doivent
     * appartenir à la MÊME UO que le projet (un type d'une autre UO n'aurait
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
                "Les types de documents attendus doivent appartenir à la même unité organisationnelle que le projet");
        }

        return types;
    }

    /**
     * Seul un EDITOR de la PROPRE UO concernée peut créer un projet — ni
     * ADMIN_UO ni ADMIN (droit de lecture seulement, voir Javadoc de la
     * classe), ni un EDITOR d'une autre UO. Utilisé uniquement à la création
     * (aucun projet n'existe encore pour tester une éventuelle appartenance
     * au groupe) — voir peutGererProjet pour les actions sur un projet
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
     * Autorité d'action sur un projet EXISTANT (types attendus, suppression) :
     *   - PUBLIC  : tout éditeur de la propre UO du projet (comme à la création).
     *   - PRIVÉ   : éditeur ET membre du groupe d'accès — un éditeur de l'UO qui
     *     n'est pas membre ne doit rien pouvoir faire sur un projet privé,
     *     sans quoi la confidentialité serait contournable en devinant l'id.
     */
    private boolean peutGererProjet(Projet projet, User acteur)
    {
        boolean estEditeur = acteur.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.EDITOR);
        if (!estEditeur)
        {
            return false;
        }

        if (projet.getAccess() == TypeAccess.PRIVE)
        {
            return peutGererGroupeProjet(projet.getGroupe(), acteur.getId());
        }

        return uniteOrganisationnelleService.getUOActuelleUser(acteur.getId())
            .map(dto -> projet.getUniteOrganisationnelle().getId().equals(dto.getId()))
            .orElse(false);
    }

    private void verifierPeutGererProjet(Projet projet, User acteur)
    {
        if (!peutGererProjet(projet, acteur))
        {
            throw new AccessDeniedException(
                projet.getAccess() == TypeAccess.PRIVE
                    ? "Ce projet est privé — seul un éditeur membre de son groupe d'accès peut effectuer cette action"
                    : "Seul un éditeur de cette unité organisationnelle peut effectuer cette action");
        }
    }
}
