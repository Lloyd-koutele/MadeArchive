package made.archive.service.organisation;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import made.archive.dto.MembreUODto;
import made.archive.dto.UOCheminProjection;
import made.archive.dto.UniteOrganisationnelleDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.MembreUniteOrganisationnelle;
import made.archive.entite.NotificationType;
import made.archive.entite.Role_Name;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.exception.UONomDejeExistantException;
import made.archive.exception.UONonVideException;
import made.archive.exception.UONotFoundException;
import made.archive.exception.UserNotFoundException;
import made.archive.repository.MembreUORepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.repository.UniteOrganisationnelleRepository.UOParentProjection;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class UniteOrganisationnelleService
{
    private static final Logger log = LoggerFactory.getLogger(UniteOrganisationnelleService.class);

    private final UniteOrganisationnelleRepository uoRepository;
    private final UserRepository userRepository;
    private final TypeDocumentRepository typeDocumentRepository;
    private final MembreUORepository membreUORepository;
    // Pas de dépendance circulaire ici : AuditLogService, côté lecture, ne dépend PAS de ce
    // service (voir AuditLogController, qui résout lui-même le scoping ADMIN_UO) — seul ce
    // sens d'appel (écriture des logs) existe.
    private final AuditLogService auditLogService;
    private final UOTreeCacheService uoTreeCacheService;
    // Idem : NotificationService (écriture seule ici) ne dépend pas de ce service.
    private final NotificationService notificationService;

    public UniteOrganisationnelleService(UniteOrganisationnelleRepository uoRepository, UserRepository userRepository, TypeDocumentRepository typeDocumentRepository, MembreUORepository membreUORepository, AuditLogService auditLogService, UOTreeCacheService uoTreeCacheService, NotificationService notificationService)
    {
        this.uoRepository = uoRepository;
        this.userRepository = userRepository;
        this.typeDocumentRepository = typeDocumentRepository;
        this.membreUORepository = membreUORepository;
        this.auditLogService = auditLogService;
        this.uoTreeCacheService = uoTreeCacheService;
        this.notificationService = notificationService;
    }

    @Transactional
    public UniteOrganisationnelleDto creatUO(UniteOrganisationnelleDto dto, User createBy)
    {
        UniteOrganisationnelle parent = null;

        if (dto.getParentId() == null)
        {
            if (!isAdmin(createBy))
                throw new AccessDeniedException("Seul un ADMIN peut créer une UO racine");
        }
        else
        {
            parent = uoRepository.findById(dto.getParentId())
                .orElseThrow(() -> new UONotFoundException(dto.getParentId()));

            if (!aAutoriteSur(dto.getParentId(), createBy))
                throw new AccessDeniedException(
                    "Vous devez être ADMIN, ou ADMIN_UO ayant autorité sur l'UO parente, pour créer une UO enfant");
        }

        verifierNomUnique(dto.getNom(), dto.getParentId());

        UniteOrganisationnelle nouvelleUO = new UniteOrganisationnelle();
        nouvelleUO.setNom(dto.getNom());
        nouvelleUO.setParent(parent);

        UniteOrganisationnelle saved = uoRepository.save(nouvelleUO);
        uoTreeCacheService.evictArbre();

        auditLogService.log(createBy, AuditAction.UO_CREEE, AuditCible.UNITE_ORGANISATIONNELLE,
            saved.getId().toString(), saved.getId(),
            "Création de l'UO " + saved.getNom()
                + (parent != null ? " (sous " + parent.getNom() + ")" : " (racine)"), true);

        notifierCreationUO(saved, parent, createBy);

        return toDTOAvecChemin(saved);
    }

    /**
     * Notifie la création d'une UO — best-effort, ne doit jamais faire
     * échouer la création elle-même. UO racine : uniquement les ADMIN
     * globaux. UO enfant : les ADMIN globaux + les ADMIN_UO ayant autorité
     * sur l'UO parente (elle-même ou un ancêtre — voir getAdminUOAvecAutoriteSur).
     */
    private void notifierCreationUO(UniteOrganisationnelle nouvelleUO, UniteOrganisationnelle parent, User createur)
    {
        try
        {
            List<User> destinataires = new ArrayList<>(userRepository.findByRoleName(Role_Name.ADMIN));
            String message;

            if (parent == null)
            {
                message = "Une nouvelle unité organisationnelle racine \"" + nouvelleUO.getNom()
                    + "\" a été créée par " + createur.getPrenom() + " " + createur.getNom() + ".";
            }
            else
            {
                destinataires.addAll(getAdminUOAvecAutoriteSur(parent.getId()));
                message = "Une nouvelle unité organisationnelle \"" + nouvelleUO.getNom()
                    + "\" a été créée sous \"" + parent.getNom() + "\" par "
                    + createur.getPrenom() + " " + createur.getNom() + ".";
            }

            notificationService.notifier(destinataires, NotificationType.UO_CREEE, message);
        }
        catch (Exception e)
        {
            log.warn("[UO] Notification (best-effort) échouée pour l'UO {} : {}",
                nouvelleUO.getId(), e.getMessage());
        }
    }

    @Transactional
    public UniteOrganisationnelleDto renommerUO(Long id, String nouveauNom, User currentUser)
    {
        if (nouveauNom == null || nouveauNom.isBlank())
        {
            throw new BusinessException("Le nom est obligatoire");
        }

        if (!aAutoriteSur(id, currentUser))
            throw new AccessDeniedException("Vous n'avez pas l'autorité sur cette UO");

        UniteOrganisationnelle uo = uoRepository.findById(id)
            .orElseThrow(() -> new UONotFoundException(id));

        if (!uo.getNom().equals(nouveauNom))
        {
            Long parentId = uo.getParent() != null ? uo.getParent().getId() : null;
            verifierNomUniqueExclut(nouveauNom, parentId, id);
            uo.setNom(nouveauNom);
            uoRepository.save(uo);
        }

        return toDTOAvecChemin(uo);
    }

    @Transactional
    public UniteOrganisationnelleDto getUOById(Long id, User currentUser)
    {
        if (!aAutoriteSur(id, currentUser))
            throw new AccessDeniedException("Vous n'avez pas l'autorité sur cette UO");

        UniteOrganisationnelle uo = uoRepository.findById(id)
            .orElseThrow(() -> new UONotFoundException(id));

        return toDTOAvecChemin(uo);
    }

    @Transactional
    public UniteOrganisationnelleDto getMonUO(User currentUser)
    {
        if (currentUser == null)
        {
            throw new BusinessException("Utilisateur non authentifié");
        }

        Long uoId = getUOActuelleId(currentUser.getId())
            .orElseThrow(() -> new BusinessException("Aucune unité organisationnelle rattachée à ce compte"));

        UniteOrganisationnelle uo = uoRepository.findById(uoId)
            .orElseThrow(() -> new UONotFoundException(uoId));

        return toDTOAvecChemin(uo);
    }

    @Transactional
    public List<UniteOrganisationnelleDto> getAllUOs()
    {
        List<UniteOrganisationnelle> uos = uoRepository.findAll();

        Map<Long, String> chemins = chargerCheminComplets();

        return uos.stream()
            .map(uo -> toDto(uo, chemins.get(uo.getId())))
            .toList();
    }

    @Transactional
    public List<UniteOrganisationnelleDto> getUOsFilles(Long parentId, User currentUser)
    {
        if (!aAutoriteSur(parentId, currentUser))
            throw new AccessDeniedException("Vous n'avez pas l'autorité sur cette UO");

        List<UniteOrganisationnelle> uos = uoRepository.findByParentId(parentId);
        Map<Long, String> chemins = chargerCheminComplets();

        return uos.stream()
            .map(uo -> toDto(uo, chemins.get(uo.getId())))
            .toList();
    }

    @Transactional
    public List<UniteOrganisationnelleDto> getSousArbre(Long racineId, User currentUser)
    {
        if (!aAutoriteSur(racineId, currentUser))
            throw new AccessDeniedException("Vous n'avez pas l'autorité sur cette UO");

        UniteOrganisationnelle racine = uoRepository.findById(racineId)
            .orElseThrow(() -> new UONotFoundException(racineId));

        List<UniteOrganisationnelle> resultat = new ArrayList<>();
        Deque<UniteOrganisationnelle> aTraiter = new ArrayDeque<>();
        aTraiter.push(racine);

        while (!aTraiter.isEmpty())
        {
            UniteOrganisationnelle courant = aTraiter.pop();
            resultat.add(courant);
            uoRepository.findByParentId(courant.getId()).forEach(aTraiter::push);
        }

        Map<Long, String> chemins = chargerCheminComplets();
        return resultat.stream()
            .map(uo -> toDto(uo, chemins.get(uo.getId())))
            .toList();
    }

    @Transactional
    public List<MembreUODto> getMembres(Long uoId, User currentUser)
    {
        if (!aAutoriteSur(uoId, currentUser))
            throw new AccessDeniedException("Vous n'avez pas l'autorité sur cette UO");

        uoRepository.findById(uoId)
            .orElseThrow(() -> new UONotFoundException(uoId));

        return membreUORepository.findByUniteOrganisationnelleId(uoId).stream()
            .map(m -> new MembreUODto(
                m.getUser().getId(),
                m.getUser().getNom(),
                m.getUser().getPrenom(),
                m.getUser().getEmail(),
                m.getDateAjout(),
                m.getActif(),
                m.getDateRetrait(),
                m.getRetirePar() != null ? m.getRetirePar().getNom() + " " + m.getRetirePar().getPrenom() : null
            ))
            .toList();
    }

    @Transactional
    public UniteOrganisationnelleDto updateUO(Long id, UniteOrganisationnelleDto dto, User currentUser)
    {
        if (!aAutoriteSur(id, currentUser))
            throw new AccessDeniedException("Vous n'avez pas l'autorisation pour modifier cette UO");

        UniteOrganisationnelle uo = uoRepository.findById(id)
            .orElseThrow(() -> new UONotFoundException(id));

        if (dto.getParentId() != null && dto.getParentId().equals(id))
        {
            throw new IllegalArgumentException("Une UO ne peut pas être son propre parent");
        }

        Long parentIdEffectif = dto.getParentId() != null
            ? dto.getParentId()
            : (uo.getParent() != null ? uo.getParent().getId() : null);

        String nomEffectif = (dto.getNom() != null && !dto.getNom().isBlank())
            ? dto.getNom()
            : uo.getNom();

        boolean nomChange = !nomEffectif.equals(uo.getNom());
        boolean parentChange = dto.getParentId() != null
                && !dto.getParentId().equals(uo.getParent() != null ? uo.getParent().getId() : null);

        if (nomChange || parentChange)
        {
            verifierNomUniqueExclut(nomEffectif, parentIdEffectif, id);
        }

        if (dto.getNom() != null && !dto.getNom().isBlank())
        {
            uo.setNom(dto.getNom());
        }

        if (dto.getParentId() != null)
        {
            if (dto.getParentId().equals(id))
            {
                throw new IllegalArgumentException("Une UO ne peut pas être son propre parent");
            }

            if (!aAutoriteSur(dto.getParentId(), currentUser))
                throw new AccessDeniedException("Vous n'avez pas l'autorité sur l'UO de destination");

            UniteOrganisationnelle nouveauParent = uoRepository.findById(dto.getParentId())
                .orElseThrow(() -> new UONotFoundException(dto.getParentId()));

            verifierPasDeCycle(uo, nouveauParent);
            uo.setParent(nouveauParent);
        }

        UniteOrganisationnelle updated = uoRepository.save(uo);

        if (parentChange)
        {
            // Seul un changement de PARENT modifie la forme de l'arbre — un
            // simple renommage (nomChange) n'a pas besoin d'évincer ce cache.
            uoTreeCacheService.evictArbre();
        }

        if (nomChange || parentChange)
        {
            auditLogService.log(currentUser, AuditAction.UO_MODIFIEE, AuditCible.UNITE_ORGANISATIONNELLE,
                updated.getId().toString(), updated.getId(),
                "Modification de l'UO " + updated.getNom(), true);
        }

        return toDTOAvecChemin(updated);
    }

    @Transactional
    public UniteOrganisationnelleDto deplacerVersRacine(Long id, User currentUser)
    {
        if (!isAdmin(currentUser))
            throw new AccessDeniedException("Seul un ADMIN peut déplacer une UO vers la racine");

        UniteOrganisationnelle uo = uoRepository.findById(id)
            .orElseThrow(() -> new UONotFoundException(id));

        if (uo.getParent() == null)
        {
            return toDTOAvecChemin(uo);
        }

        verifierNomUniqueExclut(uo.getNom(), null, id);

        uo.setParent(null);
        UniteOrganisationnelle updated = uoRepository.save(uo);
        uoTreeCacheService.evictArbre();

        auditLogService.log(currentUser, AuditAction.UO_RACINE_CHANGEE, AuditCible.UNITE_ORGANISATIONNELLE,
            updated.getId().toString(), updated.getId(),
            "L'UO " + updated.getNom() + " devient une UO racine", true);

        return toDTOAvecChemin(updated);
    }

    @Transactional
    public void supprimer(Long id, UUID userId)
    {
        User currentUser = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));

        if (!aAutoriteSur(id, currentUser))
            throw new AccessDeniedException("Vous n'avez pas l'autorisation pour supprimer cette UO");

        UniteOrganisationnelle uo = uoRepository.findById(id)
            .orElseThrow(() -> new UONotFoundException(id));

        boolean aDesEnfants = uoRepository.hasChildren(id);
        boolean aDesUtilisateurs = membreUORepository.countByUniteOrganisationnelleIdAndActifTrue(id) > 0;
        boolean aDesTypeDocuments = !typeDocumentRepository.findByUniteOrganisationnelleId(id).isEmpty();

        if (aDesEnfants || aDesUtilisateurs || aDesTypeDocuments)
        {
            StringBuilder erreurs = new StringBuilder("Impossible de supprimer l'UO '" + uo.getNom() + "' : ");
            if (aDesEnfants) erreurs.append("elle possède des UO enfants ; ");
            if (aDesUtilisateurs) erreurs.append("elle possède des utilisateurs ; ");
            if (aDesTypeDocuments) erreurs.append("elle possède des types de documents ; ");

            throw new UONonVideException(erreurs.toString());
        }

        String nomSupprime = uo.getNom();
        uoRepository.delete(uo);
        uoTreeCacheService.evictArbre();

        // uoId = l'UO elle-même : elle vient d'être supprimée, mais un ADMIN_UO parent
        // reste légitimement intéressé par cette trace (elle était dans son sous-arbre).
        auditLogService.log(currentUser, AuditAction.UO_SUPPRIMEE, AuditCible.UNITE_ORGANISATIONNELLE,
            id.toString(), id, "Suppression de l'UO " + nomSupprime, true);
    }

    private void ajouterMembreInterne(UniteOrganisationnelle uo, User user, User addedBy)
    {
        Optional<MembreUniteOrganisationnelle> actif = membreUORepository.findByUserIdAndActifTrue(user.getId());

        if (actif.isPresent())
        {
            if (actif.get().getUniteOrganisationnelle().getId().equals(uo.getId()))
                return;

            throw new AccessDeniedException(
                "Cet utilisateur appartient déjà à une unité organisationnelle. Utilisez le changement d'UO pour le déplacer.");
        }

        MembreUniteOrganisationnelle membre = new MembreUniteOrganisationnelle();
        membre.setUser(user);
        membre.setUniteOrganisationnelle(uo);
        membre.setAjoutePar(addedBy);
        membre.setDateAjout(LocalDateTime.now());
        membre.setActif(true);
        membreUORepository.save(membre);
    }

    @Transactional
    public void ajouterMembre(Long uoId, UUID userId, User demandePar)
    {
        if (userId.equals(demandePar.getId()))
            throw new AccessDeniedException("Vous ne pouvez pas ajouter votre propre UO");

        if (!aAutoriteSur(uoId, demandePar))
            throw new AccessDeniedException("Vous n'avez pas l'autorisation d'ajouter un membre à cette UO");

        UniteOrganisationnelle uo = uoRepository.findById(uoId)
            .orElseThrow(() -> new UONotFoundException(uoId));
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));

        ajouterMembreInterne(uo, user, demandePar);

        auditLogService.log(demandePar, AuditAction.UO_MEMBRE_AJOUTE, AuditCible.UNITE_ORGANISATIONNELLE,
            uoId.toString(), uoId, user.getEmail() + " ajouté à l'UO " + uo.getNom(), true);
    }

    @Transactional
    public void retirerMembre(Long uoId, UUID userId, User admin)
    {
        User cible = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));

        if (userId.equals(admin.getId()))
            throw new AccessDeniedException("Vous ne pouvez pas retirer votre propre UO");
        if (isAdmin(cible) || isAdminUO(cible))
            throw new AccessDeniedException("Vous ne pouvez pas retirer l'admin de cette UO");

        if (!aAutoriteSur(uoId, admin))
            throw new AccessDeniedException("Vous n'avez pas l'autorisation de modifier cette UO");

        MembreUniteOrganisationnelle membership = membreUORepository.findByUserIdAndActifTrue(userId)
            .filter(m -> m.getUniteOrganisationnelle().getId().equals(uoId))
            .orElseThrow(() -> new AccessDeniedException("Cet utilisateur n'est pas membre actif de cette UO"));

        membership.setActif(false);
        membership.setDateRetrait(LocalDateTime.now());
        membership.setRetirePar(admin);
        membreUORepository.save(membership);

        auditLogService.log(admin, AuditAction.UO_MEMBRE_RETIRE, AuditCible.UNITE_ORGANISATIONNELLE,
            uoId.toString(), uoId, cible.getEmail() + " retiré de l'UO", true);
    }

    @Transactional
    public void retirerMembreAndAdmin(Long uoId, UUID userId, User admin)
    {
        if (userId.equals(admin.getId()))
            throw new AccessDeniedException("Vous ne pouvez pas retirer votre propre UO");

        if (!aAutoriteSur(uoId, admin))
            throw new AccessDeniedException("Vous n'avez pas l'autorisation de retirer cet ADMIN_UO");

        User cible = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));

        MembreUniteOrganisationnelle membership = membreUORepository.findByUserIdAndActifTrue(userId)
            .filter(m -> m.getUniteOrganisationnelle().getId().equals(uoId))
            .orElseThrow(() -> new AccessDeniedException("Cet utilisateur n'est pas membre actif de cette UO"));

        membership.setActif(false);
        membership.setDateRetrait(LocalDateTime.now());
        membership.setRetirePar(admin);
        membreUORepository.save(membership);

        auditLogService.log(admin, AuditAction.UO_MEMBRE_RETIRE, AuditCible.UNITE_ORGANISATIONNELLE,
            uoId.toString(), uoId, cible.getEmail() + " (ADMIN_UO) retiré de l'UO", true);
    }

    private boolean isAdmin(User user)
    {
        if (user == null || user.getRoles() == null)
        {
            return false;
        }
        return user.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.ADMIN);
    }

    private boolean isAdminUO(User user)
    {
        if (user == null || user.getRoles() == null)
        {
            return false;
        }
        return user.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.ADMIN_UO);
    }

    private void verifierNomUnique(String nom, Long parentId)
    {
        boolean existe = (parentId == null)
            ? uoRepository.existsByNomIgnoreCaseAndParentIsNull(nom)
            : uoRepository.existsByNomIgnoreCaseAndParentId(nom, parentId);

        if (existe)
        {
            throw new UONonVideException("Une UO avec le nom " + nom + "existe deja");
        }
    }

    private void verifierNomUniqueExclut(String nom, Long parentId, Long exclutId)
    {
        List<UniteOrganisationnelle> fratrie = (parentId == null)
            ? uoRepository.findByParentIsNull()
            : uoRepository.findByParentId(parentId);

        boolean conflit = fratrie.stream()
            .anyMatch(u -> u.getNom().equalsIgnoreCase(nom) && !u.getId().equals(exclutId));

        if (conflit)
        {
            throw new UONomDejeExistantException(nom);
        }
    }

    private void verifierPasDeCycle(UniteOrganisationnelle uo, UniteOrganisationnelle nouveauParent)
    {
        UniteOrganisationnelle courant = nouveauParent;
        while (courant != null)
        {
            if (courant.getId().equals(uo.getId()))
            {
                throw new IllegalArgumentException("Déplacement invalide : créerait un cycle dans la hiérarchie des UO");
            }
            courant = courant.getParent();
        }
    }

    private Map<Long, String> chargerCheminComplets()
    {
        return uoRepository.findAllCheminsComplets()
            .stream()
            .collect(Collectors.toMap(UOCheminProjection::getId, UOCheminProjection::getChemin));
    }

    private UniteOrganisationnelleDto toDTOAvecChemin(UniteOrganisationnelle uo)
    {
        String chemin = uoRepository.findCheminCompletById(uo.getId())
            .orElse(uo.getNom());
        return toDto(uo, chemin);
    }

    private UniteOrganisationnelleDto toDto(UniteOrganisationnelle uo, String cheminComplet)
    {
        return new UniteOrganisationnelleDto(
            uo.getId(),
            uo.getNom(),
            uo.getParent() != null ? uo.getParent().getId() : null,
            cheminComplet,
            null,
            null
        );
    }

    private List<Long> cheminVersRacine(Long uoId)
    {
        List<Long> chemin = new ArrayList<>();
        UniteOrganisationnelle courant = uoRepository.findById(uoId).orElse(null);
        while (courant != null)
        {
            chemin.add(courant.getId());
            courant = courant.getParent();
        }
        return chemin;
    }

    private Optional<Long> getUOActuelleId(UUID userId)
    {
        return membreUORepository.findByUserIdAndActifTrue(userId)
            .map(m -> m.getUniteOrganisationnelle().getId());
    }

    @Transactional
    public Optional<UniteOrganisationnelleDto> getUOActuelleUser(UUID userId)
    {
        return getUOActuelleId(userId).map(uoId -> {
            UniteOrganisationnelle uo = uoRepository.findById(uoId)
                .orElseThrow(() -> new UONotFoundException(uoId));

            return toDTOAvecChemin(uo);
        });
    }

    /**
     * Retourne l'entité UO actuelle (active) d'un utilisateur.
     * Utilisé notamment à l'upload d'un document, pour rattacher le document
     * à l'UO de l'éditeur (scope de la détection de doublons).
     */
    @Transactional
    public UniteOrganisationnelle getUOActuelleEntite(UUID userId)
    {
        Long uoId = getUOActuelleId(userId)
            .orElseThrow(() -> new BusinessException(
                "Cet utilisateur n'appartient à aucune unité organisationnelle active"));

        return uoRepository.findById(uoId)
            .orElseThrow(() -> new UONotFoundException(uoId));
    }

    public boolean aAutoriteSur(Long uoCibleId, User acteur)
    {
        if (isAdmin(acteur))
            return true;

        if (!isAdminUO(acteur))
            return false;

        Optional<Long> acteurUOId = getUOActuelleId(acteur.getId());
        if (acteurUOId.isEmpty())
            return false;

        return cheminVersRacine(uoCibleId).contains(acteurUOId.get());
    }

    /**
     * Tous les ADMIN_UO ayant autorité sur cette UO — leur propre UO est
     * cette UO elle-même ou un de ses ancêtres (même logique que aAutoriteSur,
     * mais dans l'autre sens : de la cible vers les responsables).
     * Utilisé pour résoudre les destinataires de notification (document
     * corrompu, projet créé...). N'inclut PAS les ADMIN globaux — à ajouter
     * séparément si besoin (ils ne sont rattachés à aucune UO).
     */
    @Transactional
    public List<User> getAdminUOAvecAutoriteSur(Long uoId)
    {
        List<Long> chemin = cheminVersRacine(uoId);
        if (chemin.isEmpty())
        {
            return List.of();
        }

        return membreUORepository.findByUniteOrganisationnelleIdInAndActifTrue(chemin).stream()
            .map(MembreUniteOrganisationnelle::getUser)
            .filter(this::isAdminUO)
            .toList();
    }

    @Transactional
    public void changerUOUtilisateur(UUID userId, Long nouvelUoId, User demandePar)
    {
        User cible = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));

        if (cible.getId().equals(demandePar.getId()))
            throw new AccessDeniedException("Vous ne pouvez pas changer votre propre UO");

        if (isAdmin(cible))
            throw new AccessDeniedException("Un ADMIN n'est pas rattaché à une UO");

        MembreUniteOrganisationnelle ancienneMembership = membreUORepository.findByUserIdAndActifTrue(userId)
            .orElseThrow(() -> new AccessDeniedException("Cet utilisateur n'appartient à aucune UO"));

        if (!aAutoriteSur(ancienneMembership.getUniteOrganisationnelle().getId(), demandePar))
            throw new AccessDeniedException("Vous n'avez pas l'autorité sur l'UO d'origine");
        if (!aAutoriteSur(nouvelUoId, demandePar))
            throw new AccessDeniedException("Vous n'avez pas l'autorité sur l'UO de destination");

        UniteOrganisationnelle nouvelleUO = uoRepository.findById(nouvelUoId)
            .orElseThrow(() -> new UONotFoundException(nouvelUoId));

        ancienneMembership.setActif(false);
        ancienneMembership.setDateRetrait(LocalDateTime.now());
        ancienneMembership.setRetirePar(demandePar);
        membreUORepository.save(ancienneMembership);

        MembreUniteOrganisationnelle nouvelleMembership = new MembreUniteOrganisationnelle();
        nouvelleMembership.setUser(cible);
        nouvelleMembership.setUniteOrganisationnelle(nouvelleUO);
        nouvelleMembership.setAjoutePar(demandePar);
        nouvelleMembership.setDateAjout(LocalDateTime.now());
        nouvelleMembership.setActif(true);
        membreUORepository.save(nouvelleMembership);

        auditLogService.log(demandePar, AuditAction.UO_MEMBRE_TRANSFERE, AuditCible.UNITE_ORGANISATIONNELLE,
            nouvelUoId.toString(), nouvelUoId,
            cible.getEmail() + " transféré vers l'UO " + nouvelleUO.getNom(), true,
            Map.of("uoOrigine", ancienneMembership.getUniteOrganisationnelle().getNom(),
                   "uoDestination", nouvelleUO.getNom()));
    }

    public boolean aUOActive(UUID userId)
    {
        return membreUORepository.existsByUserIdAndActifTrue(userId);
    }

    @Transactional
    public void retirerUOPourPromotion(UUID userId, User admin)
    {
        if (!isAdmin(admin))
            throw new AccessDeniedException("Seul un ADMIN peut promouvoir un utilisateur au rang ADMIN");

        membreUORepository.findByUserIdAndActifTrue(userId).ifPresent(membership -> {
            membership.setActif(false);
            membership.setDateRetrait(LocalDateTime.now());
            membership.setRetirePar(admin);
            membreUORepository.save(membership);
        });
    }

    @Transactional
    public List<User> getUtilisateursDeUO(Long uoId, User currentUser)
    {
        // Autorisé si : autorité admin classique (ADMIN, ou ADMIN_UO sur cette UO ou une
        // ancêtre — usage historique : gestion des membres depuis AdminUoDashboard), OU
        // c'est la propre UO actuelle de l'appelant — sans quoi un EDITOR ne pouvait jamais
        // lister ses propres collègues d'UO (ex. choix des membres d'un groupe de partage),
        // alors qu'aAutoriteSur ne reconnaît que ADMIN/ADMIN_UO comme "autorités".
        boolean estSaPropreUO = uoId.equals(getUOActuelleId(currentUser.getId()).orElse(null));
        if (!aAutoriteSur(uoId, currentUser) && !estSaPropreUO)
        {
            throw new AccessDeniedException("Vous n'avez pas l'autorité sur cette UO");
        }

        uoRepository.findById(uoId)
            .orElseThrow(() -> new UONotFoundException(uoId));

        List<MembreUniteOrganisationnelle> memberships = membreUORepository.findByUniteOrganisationnelleId(uoId);

        // 1. Utilisateurs actifs dans cette UO
        List<User> actifs = memberships.stream()
            .filter(MembreUniteOrganisationnelle::getActif)
            .map(MembreUniteOrganisationnelle::getUser)
            .toList();

        // 2. Dernier retrait le plus récent par utilisateur
        Map<UUID, MembreUniteOrganisationnelle> dernierRetraitParUser = memberships.stream()
            .filter(m -> !m.getActif())
            .collect(Collectors.toMap(
                m -> m.getUser().getId(),
                m -> m,
                (a, b) -> {
                    if (a.getDateRetrait() == null) return b;
                    if (b.getDateRetrait() == null) return a;
                    return a.getDateRetrait().isAfter(b.getDateRetrait()) ? a : b;
                }
            ));

        // 3. Utilisateurs sans UO active — vérifié en UNE requête batch au lieu d'une par utilisateur
        List<UUID> candidatsIds = dernierRetraitParUser.values().stream()
            .map(m -> m.getUser().getId())
            .toList();

        Set<UUID> actifsAilleurs = candidatsIds.isEmpty()
            ? Set.of()
            : membreUORepository.findByUserIdInAndActifTrue(candidatsIds).stream()
                .map(m -> m.getUser().getId())
                .collect(Collectors.toSet());

        List<User> enAttente = dernierRetraitParUser.values().stream()
            .map(MembreUniteOrganisationnelle::getUser)
            .filter(u -> !actifsAilleurs.contains(u.getId()))
            .toList();

        // 4. Fusion
        List<User> resultat = new ArrayList<>(actifs);
        resultat.addAll(enAttente);
        return resultat;
    }

    public boolean aAutoriteSurUtilisateur(UUID cibleUserId, User acteur)
    {
        if (isAdmin(acteur))
            return true;

        Optional<Long> uoCibleId = getUOActuelleId(cibleUserId);
        if (uoCibleId.isEmpty())
            return false;

        return aAutoriteSur(uoCibleId.get(), acteur);
    }

    // Types Documents

    public UniteOrganisationnelle getUOEntiteAvecAutorite(Long uoId, User currentUser)
    {
        if (!aAutoriteSur(uoId, currentUser))
            throw new AccessDeniedException("Vous n'avez pas l'autorité sur cette UO");

        return uoRepository.findById(uoId)
            .orElseThrow(() -> new UONotFoundException(uoId));
    }

    @Transactional
    public Map<UUID, UniteOrganisationnelleDto> getUOActuellesUsers(Collection<UUID> userIds)
    {
        if (userIds == null || userIds.isEmpty())
        {
            return Map.of();
        }

        List<MembreUniteOrganisationnelle> memberships = membreUORepository.findByUserIdInAndActifTrue(userIds);
        if (memberships.isEmpty())
        {
            return Map.of();
        }

        Map<Long, String> chemins = chargerCheminComplets(); // 1 seule requête pour tous

        return memberships.stream().collect(Collectors.toMap(
            m -> m.getUser().getId(),
            m -> toDto(m.getUniteOrganisationnelle(), chemins.get(m.getUniteOrganisationnelle().getId()))
        ));
    }

   
    /**
     * La requête elle-même est mise en cache par UOTreeCacheService (Redis) —
     * cette méthode reconstruit juste la map en mémoire à partir du résultat
     * (mis en cache ou frais), opération locale bon marché à chaque appel.
     */
    private Map<Long, List<Long>> chargerArbreEnfants()
    {
        Map<Long, List<Long>> enfantsParParent = new HashMap<>();
        for (made.archive.dto.UOParentIdPair p : uoTreeCacheService.chargerLiaisons())
        {
            if (p.parentId() != null)
            {
                enfantsParParent.computeIfAbsent(p.parentId(), k -> new ArrayList<>()).add(p.id());
            }
        }
        return enfantsParParent;
    }

    
    @Transactional
    public Set<UUID> getUtilisateursAutorisesIds(User acteur)
    {
        if (isAdmin(acteur))
        {
            return null; // signal : aucun filtrage nécessaire
        }

        if (!isAdminUO(acteur))
        {
            return Set.of();
        }

        Long racineId = getUOActuelleId(acteur.getId()).orElse(null);
        if (racineId == null)
        {
            return Set.of();
        }

        Map<Long, List<Long>> arbre = chargerArbreEnfants(); // 1 requête

        List<Long> sousArbre = new ArrayList<>();
        sousArbre.add(racineId);
        Deque<Long> aTraiter = new ArrayDeque<>();
        aTraiter.push(racineId);

        while (!aTraiter.isEmpty())
        {
            Long courant = aTraiter.pop();
            List<Long> enfants = arbre.getOrDefault(courant, List.of());
            sousArbre.addAll(enfants);
            enfants.forEach(aTraiter::push);
        }

        return membreUORepository.findByUniteOrganisationnelleIdInAndActifTrue(sousArbre).stream()
            .map(m -> m.getUser().getId())
            .collect(Collectors.toSet());
    }

    /**
     * Même sémantique de retour que {@link #getUtilisateursAutorisesIds} (null = ADMIN
     * global, aucun filtrage), mais renvoie les UO elles-mêmes (l'UO de l'acteur + tout
     * son sous-arbre) plutôt que les utilisateurs qui s'y trouvent. Utilisé pour scoper
     * la consultation du journal d'audit aux ADMIN_UO.
     */
    @Transactional
    public Set<Long> getUoIdsSousAutorite(User acteur)
    {
        if (isAdmin(acteur))
        {
            return null; // signal : aucun filtrage nécessaire
        }

        if (!isAdminUO(acteur))
        {
            return Set.of();
        }

        Long racineId = getUOActuelleId(acteur.getId()).orElse(null);
        if (racineId == null)
        {
            return Set.of();
        }

        return sousArbreDe(racineId);
    }

    /**
     * UO dont les DOCUMENTS sont visibles en lecture pour cet acteur — utilisé
     * par DocumentAccessService et DocumentSearchService pour scoper les
     * résultats de recherche/listage de documents.
     *
     * Diffère délibérément de {@link #getUoIdsSousAutorite} : celle-ci renvoie
     * un ensemble VIDE pour EDITOR/USER, ce qui est correct pour le journal
     * d'audit (ils n'y ont aucun accès), mais serait faux ici — un EDITOR ou un
     * USER doit voir les documents de sa PROPRE UO, jamais zéro. On renvoie donc
     * un singleton {leurUOId} pour eux, et le même sous-arbre {UO + descendantes}
     * que getUoIdsSousAutorite pour un ADMIN_UO.
     *
     * null = ADMIN global, aucun filtrage nécessaire (même convention que
     * getUoIdsSousAutorite / getUtilisateursAutorisesIds).
     */
    @Transactional
    public Set<Long> getUoIdsVisiblesPourLecture(User acteur)
    {
        if (isAdmin(acteur))
        {
            return null; // signal : aucun filtrage nécessaire
        }

        Long racineId = getUOActuelleId(acteur.getId()).orElse(null);
        if (racineId == null)
        {
            return Set.of();
        }

        if (!isAdminUO(acteur))
        {
            // EDITOR / USER : uniquement leur propre UO, jamais les UO enfants
            // (à la différence d'ADMIN_UO ci-dessous) — voir la règle établie
            // pour AdminUoDashboard/UOLectureController plus tôt dans ce projet.
            return Set.of(racineId);
        }

        return sousArbreDe(racineId);
    }

    /**
     * Racine + tout son sous-arbre d'UO descendantes. Partagé par
     * getUoIdsSousAutorite() et getUoIdsVisiblesPourLecture().
     */
    private Set<Long> sousArbreDe(Long racineId)
    {
        Map<Long, List<Long>> arbre = chargerArbreEnfants();

        Set<Long> sousArbre = new HashSet<>();
        sousArbre.add(racineId);
        Deque<Long> aTraiter = new ArrayDeque<>();
        aTraiter.push(racineId);

        while (!aTraiter.isEmpty())
        {
            Long courant = aTraiter.pop();
            List<Long> enfants = arbre.getOrDefault(courant, List.of());
            sousArbre.addAll(enfants);
            enfants.forEach(aTraiter::push);
        }

        return sousArbre;
    }
}