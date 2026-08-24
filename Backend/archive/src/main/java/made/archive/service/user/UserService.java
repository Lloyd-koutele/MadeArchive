package made.archive.service.user;

import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import made.archive.dto.UniteOrganisationnelleDto;
import made.archive.dto.UserDto;
import made.archive.dto.UserResponseDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.PkiKeyStatus;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.User;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.exception.EmailDejaUtiliseException;
import made.archive.entite.UserActiveToken;
import made.archive.repository.RoleRepository;
import made.archive.repository.UserActiveTokenRepository;
import made.archive.repository.UserRepository;
import made.archive.security.HsmKeyStoreService;
import made.archive.security.PkiService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;

@Service
public class UserService
{
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UniteOrganisationnelleService uniteOrganisationnelleService;

    @Autowired
    private PkiService pkiService;

    @Autowired
    private HsmKeyStoreService hsmKeyStoreService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private UserActiveTokenRepository activeTokenRepository;

    @Autowired
    private made.archive.security.AuthCacheService authCacheService;

    /** UO actuelle de l'utilisateur, pour le contexte du journal d'audit (null si aucune / ADMIN). */
    private Long uoDe(UUID userId)
    {
        return uniteOrganisationnelleService.getUOActuelleUser(userId)
            .map(UniteOrganisationnelleDto::getId)
            .orElse(null);
    }

    /**
     * Invalide la session déjà ouverte d'un utilisateur — appelée aux 3 seuls
     * endroits où sessionInvalidatedAt change (blocage, changement de rôle,
     * changement de mot de passe). L'éviction du cache Redis (voir
     * AuthCacheService) est centralisée ici plutôt que dupliquée à chaque site
     * d'appel : ça garantit qu'aucun futur appelant de setSessionInvalidatedAt
     * n'oublie d'évincer, tant qu'il passe par cette méthode.
     */
    private void expirerTokenActif(User user)
    {
        activeTokenRepository.findByUser(user).ifPresent(uat -> {
            uat.setExpiresAt(Instant.now());
            activeTokenRepository.save(uat);
        });
        authCacheService.evict(user.getEmail());
    }

    private List<UserResponseDto> convertUsersToDto(List<User> users)
    {
        if (users.isEmpty())
        {
            return List.of();
        }

        List<UUID> ids = users.stream().map(User::getId).toList();
        Map<UUID, UniteOrganisationnelleDto> uoParUser = uniteOrganisationnelleService.getUOActuellesUsers(ids);

        return users.stream().map(u -> {
            UniteOrganisationnelleDto uo = uoParUser.get(u.getId());
            return new UserResponseDto(
                u.getId(),
                u.getNom(),
                u.getPrenom(),
                u.getEmail(),
                u.getTelephone(),
                u.isActif(),
                u.getRoles(),
                uo != null ? uo.getId() : null,
                uo != null ? uo.getNom() : null
            );
        }).toList();
    }

    private UserDto convertToDtoWithoutPassword(User entity)
    {
        UserDto dto = new UserDto();
        dto.setNom(entity.getNom());
        dto.setPrenom(entity.getPrenom());
        dto.setEmail(entity.getEmail());
        dto.setTelephone(entity.getTelephone());
        dto.setRoles(entity.getRoles());
        dto.setPassword(null);
        return dto;
    }

    @Transactional(readOnly = true)
    public List<UserResponseDto> getAllUsers(User currentUser)
    {
        List<User> tous = userRepository.findAll();

        if (isAdmin(currentUser))
        {
            return convertUsersToDto(tous);
        }

        // null = admin (cas géré au-dessus), sinon Set des IDs autorisés (peut être vide)
        Set<UUID> autorises = uniteOrganisationnelleService.getUtilisateursAutorisesIds(currentUser);

        List<User> filtres = tous.stream()
            .filter(u -> autorises.contains(u.getId()))
            .toList();

        return convertUsersToDto(filtres);
    }

    @Transactional(readOnly = true)
    public List<UserResponseDto> getUsersByUO(Long uoId, User currentUser)
    {
        if (uoId == null)
        {
            throw new BusinessException("L'UO est obligatoire");
        }
        List<User> users = uniteOrganisationnelleService.getUtilisateursDeUO(uoId, currentUser);
        return convertUsersToDto(users);
    }

    @Transactional(readOnly = true)
    public List<UserResponseDto> getUsersByRole(Role_Name roleName)
    {
        if (roleName == null)
        {
            throw new BusinessException("Le rôle est obligatoire");
        }

        Role role = roleRepository.findByName(roleName)
            .orElseThrow(() -> new BusinessException("Rôle introuvable : " + roleName));

        List<User> users = userRepository.findByRoles(role);
        return convertUsersToDto(users);
    }

    @Transactional(readOnly = true)
    public List<User> getUsersByStatus(boolean actif)
    {
        return userRepository.findByActif(actif);
    }

    @Transactional(readOnly = true)
    public Optional<UserResponseDto> getUserById(UUID id)
    {
        if (id == null) {
            throw new BusinessException("L'ID est obligatoire");
        }
        return userRepository.findById(id).map(u -> convertUsersToDto(List.of(u)).get(0));
    }

    @Transactional(readOnly = true)
    public Optional<UserResponseDto> getMe(UUID userId)
    {
        if (userId == null) {
            throw new BusinessException("L'ID est obligatoire");
        }
        return userRepository.findById(userId).map(u -> convertUsersToDto(List.of(u)).get(0));
    }

    @Transactional(readOnly = true)
    public Optional<UserResponseDto> getUserByEmail(String email)
    {
        if (!StringUtils.hasText(email)) {
            throw new BusinessException("L'email est obligatoire");
        }
        return userRepository.findByEmail(email).map(u -> convertUsersToDto(List.of(u)).get(0));
    }

    @Transactional
    public User updateUserStatus(UUID id, UserDto dto, User currentUser)
    {
        if (dto == null || id == null || currentUser == null)
        {
            throw new BusinessException("Les données de mise à jour sont invalides");
        }

        if (currentUser.getId().equals(id))
        {
            throw new BusinessException("Vous ne pouvez pas mettre à jour votre propre statut");
        }
        User user = userRepository.findById(id)
            .orElseThrow(() -> new BusinessException("Utilisateur non trouvé avec l'ID: " + id));

        if (!uniteOrganisationnelleService.aAutoriteSurUtilisateur(id, currentUser))
        {
            throw new AccessDeniedException("Vous n'avez pas l'autorité sur cet utilisateur");
        }

        boolean etaitActif = Boolean.TRUE.equals(user.isActif());
        user.setActif(dto.isActif());

        Long uoCible = uoDe(id);

        // Blocage d'un compte jusque-là actif : toute session déjà ouverte (JWT valide en
        // main de l'utilisateur) doit être invalidée immédiatement, sans attendre son expiration.
        if (etaitActif && !dto.isActif())
        {
            user.setSessionInvalidatedAt(Instant.now());
            expirerTokenActif(user);
            auditLogService.log(currentUser, AuditAction.UTILISATEUR_BLOQUE, AuditCible.UTILISATEUR,
                id.toString(), uoCible, "Blocage du compte " + user.getEmail(), true);
            auditLogService.log(currentUser, AuditAction.SESSION_INVALIDEE, AuditCible.UTILISATEUR,
                id.toString(), uoCible, "Session invalidée suite au blocage du compte " + user.getEmail(), true);
        }
        else if (!etaitActif && dto.isActif())
        {
            auditLogService.log(currentUser, AuditAction.UTILISATEUR_REACTIVE, AuditCible.UTILISATEUR,
                id.toString(), uoCible, "Réactivation du compte " + user.getEmail(), true);
        }

        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public boolean isUserActive(UUID userId)
    {
        if (userId == null) {
            throw new BusinessException("L'ID est obligatoire");
        }
        return userRepository.findById(userId)
            .map(User::isActif)
            .orElseThrow(() -> new BusinessException("Utilisateur non trouvé avec l'ID: " + userId));
    }

    // ----------- CREATE -----------

    @Transactional
    public UserDto createUser(UserDto dto, List<Long> uoIds, User createPar)
    {
        if (userRepository.findByEmail(dto.getEmail()).isPresent())
        {
            throw new EmailDejaUtiliseException(dto.getEmail());
        }

        if (dto.getPassword() == null || dto.getPassword().isBlank())
        {
            throw new BusinessException("Le mot de passe est obligatoire");
        }

        if (userRepository.findByTelephone(dto.getTelephone()).isPresent())
        {
            throw new BusinessException("Le numéro de téléphone est déjà utilisé");
        }

        Set<Role> rolesAttribues = new HashSet<>();
        for (Role roleDto : dto.getRoles())
        {
            Role roleExistant = roleRepository.findByName(roleDto.getName())
                .orElseThrow(() -> new BusinessException("Le rôle " + roleDto.getName() + " n'existe pas"));
            rolesAttribues.add(roleExistant);
        }

        boolean estAdmin = rolesAttribues.stream().anyMatch(r -> r.getName() == Role_Name.ADMIN);

        if (estAdmin && !isAdmin(createPar))
        {
            throw new AccessDeniedException("Seul un ADMIN peut attribuer le rôle ADMIN");
        }

        if (estAdmin)
        {
            if (uoIds != null && !uoIds.isEmpty())
            {
                throw new BusinessException("Un ADMIN ne doit pas être rattaché à une unité organisationnelle");
            }
        }
        else
        {
            if (uoIds == null || uoIds.isEmpty())
            {
                throw new BusinessException("Une unité organisationnelle est obligatoire pour ce rôle");
            }
            if (uoIds.size() > 1)
            {
                throw new BusinessException("Un utilisateur ne peut appartenir qu'à une seule unité organisationnelle");
            }
        }

        User user = new User();
        user.setNom(dto.getNom());
        user.setPrenom(dto.getPrenom());
        user.setEmail(dto.getEmail());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setTelephone(dto.getTelephone());
        user.setRoles(rolesAttribues);

        User saved = userRepository.save(user);
        User executant = (createPar != null) ? createPar : saved;

        if (!estAdmin)
        {
            uniteOrganisationnelleService.ajouterMembre(uoIds.get(0), saved.getId(), executant);
        }

        boolean estEditeur = rolesAttribues.stream().anyMatch(r -> r.getName() == Role_Name.EDITOR);
        if (estEditeur)
        {
            provisionnerClePki(saved);
        }

        auditLogService.log(createPar, AuditAction.UTILISATEUR_CREE, AuditCible.UTILISATEUR,
            saved.getId().toString(), estAdmin ? null : uoIds.get(0),
            "Création de l'utilisateur " + saved.getEmail()
                + " (rôles : " + roleNamesToString(rolesAttribues) + ")",
            true);

        dto.setPassword(null);
        return dto;
    }

    private String roleNamesToString(Set<Role> roles)
    {
        return roles.stream().map(r -> r.getName().name()).collect(Collectors.joining(","));
    }

    private void provisionnerClePki(User user)
    {
        try
        {
            KeyPair keyPair = pkiService.generateNativeKeyPair();
            String alias = "editor-" + user.getId();

            hsmKeyStoreService.storePrivateKey(alias, keyPair);

            user.setPkiKeyAlias(alias);
            user.setPkiPublicKey(pkiService.encodePublicKeyToPem(keyPair.getPublic()));
            user.setPkiKeyStatus(PkiKeyStatus.ACTIVE);
            user.setPkiKeyCreatedAt(LocalDateTime.now());
            userRepository.save(user);
        }
        catch (Exception e)
        {
            throw new BusinessException(
                "Impossible de générer la clé de signature PKI pour l'éditeur " + user.getEmail(), e);
        }
    }

    // ----------- UPDATE -----------

    @Transactional
    public Optional<UserDto> updateUser(UUID id, UserDto dto, Long uoId, User currentUser)
    {
        if (dto == null || id == null || currentUser == null)
        {
            throw new BusinessException("Les données de mise à jour sont invalides");
        }

        if (currentUser.getId().equals(id))
        {
            throw new BusinessException("Vous ne pouvez pas modifier votre propre profil");
        }

        User user = userRepository.findById(id)
            .orElseThrow(() -> new BusinessException("Utilisateur non trouvé avec l'ID: " + id));

        Set<Role> nouveauxRoles = new HashSet<>();
        for (Role roleDto : dto.getRoles())
        {
            Role roleExistant = roleRepository.findByName(roleDto.getName())
                .orElseThrow(() -> new BusinessException("Rôle introuvable : " + roleDto.getName()));
            nouveauxRoles.add(roleExistant);
        }

        Set<Role_Name> rolesActuels = user.getRoles().stream().map(Role::getName).collect(Collectors.toSet());
        Set<Role_Name> rolesDemandes = nouveauxRoles.stream().map(Role::getName).collect(Collectors.toSet());
        boolean roleChange = !rolesActuels.equals(rolesDemandes);

        boolean cibleEtaitAdmin = user.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.ADMIN);
        boolean cibleSeraAdmin = nouveauxRoles.stream().anyMatch(r -> r.getName() == Role_Name.ADMIN);
        boolean cibleSeraEditeur = nouveauxRoles.stream().anyMatch(r -> r.getName() == Role_Name.EDITOR);

        if (cibleSeraAdmin && !isAdmin(currentUser))
        {
            throw new AccessDeniedException("Seul un ADMIN peut attribuer le rôle ADMIN");
        }

        if (cibleEtaitAdmin && !isAdmin(currentUser))
        {
            throw new AccessDeniedException("Vous n'avez pas l'autorisation de modifier un ADMIN");
        }

        if (!uniteOrganisationnelleService.aAutoriteSurUtilisateur(id, currentUser))
        {
            throw new AccessDeniedException("Vous n'avez pas l'autorité sur cet utilisateur");
        }

        String emailAvant = user.getEmail();
        String telephoneAvant = user.getTelephone();

        if (!user.getEmail().equalsIgnoreCase(dto.getEmail()))
        {
            userRepository.findByEmail(dto.getEmail()).ifPresent(u ->
            {
                throw new EmailDejaUtiliseException(dto.getEmail());
            });
            user.setEmail(dto.getEmail());
        }

        if (!user.getTelephone().equalsIgnoreCase(dto.getTelephone()))
        {
            userRepository.findByTelephone(dto.getTelephone()).ifPresent(u ->
            {
                throw new BusinessException("Le numéro de téléphone est déjà utilisé");
            });
            user.setTelephone(dto.getTelephone());
        }

        user.setNom(dto.getNom());
        user.setPrenom(dto.getPrenom());
        user.setTelephone(dto.getTelephone());

        boolean passwordChange = dto.getPassword() != null && !dto.getPassword().isBlank();
        if (passwordChange)
        {
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
        }

        // Rôle ou mot de passe changé par un administrateur : la session déjà ouverte de
        // l'utilisateur cible (JWT encore valide) porte potentiellement d'anciens droits ou
        // un ancien mot de passe — on force sa reconnexion.
        if (roleChange || passwordChange)
        {
            user.setSessionInvalidatedAt(Instant.now());
            expirerTokenActif(user);
        }

        user.setRoles(nouveauxRoles);
        User saved = userRepository.save(user);

        if (cibleSeraEditeur && saved.getPkiKeyAlias() == null)
        {
            provisionnerClePki(saved);
        }

        boolean aUOActive = uniteOrganisationnelleService.aUOActive(saved.getId());

        if (cibleSeraAdmin)
        {
            if (uoId != null)
            {
                throw new BusinessException("Un ADMIN ne doit pas être rattaché à une unité organisationnelle");
            }
            if (aUOActive)
            {
                uniteOrganisationnelleService.retirerUOPourPromotion(saved.getId(), currentUser);
            }
        }
        else
        {
            if (uoId != null)
            {
                if (aUOActive)
                {
                    uniteOrganisationnelleService.changerUOUtilisateur(saved.getId(), uoId, currentUser);
                }
                else
                {
                    uniteOrganisationnelleService.ajouterMembre(uoId, saved.getId(), currentUser);
                }
            }
            else if (!aUOActive)
            {
                throw new BusinessException("Une unité organisationnelle est obligatoire pour ce rôle");
            }
        }

        boolean emailChange = !emailAvant.equalsIgnoreCase(saved.getEmail());
        boolean telChange   = !telephoneAvant.equalsIgnoreCase(saved.getTelephone());
        Long uoCible = uoDe(saved.getId());

        Map<String, Object> details = new LinkedHashMap<>();
        if (roleChange) details.put("roles", Map.of("avant", rolesActuels.toString(), "apres", rolesDemandes.toString()));
        if (emailChange) details.put("email", Map.of("avant", emailAvant, "apres", saved.getEmail()));
        if (telChange) details.put("telephone", Map.of("avant", telephoneAvant, "apres", saved.getTelephone()));
        if (passwordChange) details.put("motDePasse", "changé");

        auditLogService.log(currentUser, AuditAction.UTILISATEUR_MODIFIE, AuditCible.UTILISATEUR,
            saved.getId().toString(), uoCible,
            "Modification de l'utilisateur " + saved.getEmail(), true, details);

        if (roleChange || passwordChange)
        {
            auditLogService.log(currentUser, AuditAction.SESSION_INVALIDEE, AuditCible.UTILISATEUR,
                saved.getId().toString(), uoCible,
                "Session invalidée suite à la modification de " + saved.getEmail()
                    + " (" + (roleChange ? "rôle" : "") + (roleChange && passwordChange ? " + " : "")
                    + (passwordChange ? "mot de passe" : "") + ")",
                true);
        }

        dto.setPassword(null);
        return Optional.of(dto);
    }

    @Transactional
    public Optional<UserDto> updateMe(UUID userId, UserDto dto)
    {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("L'utilisateur est introuvable"));

        user.setPrenom(dto.getPrenom());
        user.setNom(dto.getNom());
        user.setTelephone(dto.getTelephone());

        boolean passwordChange = dto.getPassword() != null && !dto.getPassword().isBlank();
        if (passwordChange) {
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
            user.setSessionInvalidatedAt(Instant.now());
            expirerTokenActif(user);
        }

        User updated = userRepository.save(user);

        Long uoCible = uoDe(userId);
        auditLogService.log(user, AuditAction.PROFIL_MODIFIE, AuditCible.UTILISATEUR,
            userId.toString(), uoCible, "Modification de son propre profil par " + user.getEmail(), true,
            passwordChange ? Map.of("motDePasse", "changé") : null);

        if (passwordChange)
        {
            auditLogService.log(user, AuditAction.SESSION_INVALIDEE, AuditCible.UTILISATEUR,
                userId.toString(), uoCible,
                "Session invalidée suite à l'auto-changement de mot de passe de " + user.getEmail(), true);
        }

        return Optional.of(convertToDtoWithoutPassword(updated));
    }

    private boolean isAdmin(User user)
    {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return false;

        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}