package made.archive.service.user;

import java.util.HashSet;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.SetupAdminRequestDto;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.RoleRepository;
import made.archive.repository.UserRepository;

/**
 * Assistant de première configuration — voie INTERACTIVE de création de
 * l'admin initial (voir InitialAdminCreation pour la voie automatisée
 * .env). Utilisable une seule fois : dès qu'un admin existe, cette voie se
 * ferme définitivement, quelle que soit celle qui l'a créé.
 *
 * Fenêtre de course assumée, pas verrouillée : deux soumissions simultanées
 * du formulaire pourraient toutes deux passer la vérification avant que
 * l'une des deux n'ait sauvegardé. Accepté comme risque négligeable — un
 * geste humain unique, juste après le premier démarrage, pas un chemin
 * chaud ni exposé à un trafic non maîtrisé.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SetupService
{
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public boolean needsSetup()
    {
        return !userRepository.existsByRoleName(Role_Name.ADMIN);
    }

    @Transactional
    public void creerAdminInitial(SetupAdminRequestDto dto)
    {
        if (userRepository.existsByRoleName(Role_Name.ADMIN))
        {
            throw new BusinessException(
                "Un administrateur existe déjà — l'assistant de première configuration "
                + "ne peut être utilisé qu'une seule fois.");
        }

        if (userRepository.existsByEmail(dto.getEmail()))
        {
            throw new BusinessException("Cet email est déjà utilisé.");
        }

        Role adminRole = roleRepository.findByName(Role_Name.ADMIN)
            .orElseGet(() -> {
                Role r = new Role();
                r.setName(Role_Name.ADMIN);
                return roleRepository.save(r);
            });

        User admin = new User();
        admin.setNom(dto.getNom());
        admin.setPrenom(dto.getPrenom());
        admin.setEmail(dto.getEmail());
        admin.setPassword(passwordEncoder.encode(dto.getPassword()));
        admin.setTelephone(dto.getTelephone());
        admin.setActif(true);

        Set<Role> roles = new HashSet<>();
        roles.add(adminRole);
        admin.setRoles(roles);

        userRepository.save(admin);

        log.info("[Setup] Administrateur initial créé via l'assistant de première configuration : {}",
            admin.getEmail());
    }
}
