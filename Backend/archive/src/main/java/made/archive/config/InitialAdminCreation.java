package made.archive.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

import made.archive.repository.UserRepository;
import made.archive.repository.RoleRepository;
import made.archive.entite.User;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;

/**
 * S'assure que les 4 rôles existent en base (indépendant de l'admin
 * lui-même — nécessaire dès le premier démarrage, que l'admin soit créé ici
 * ou via l'assistant web). Voir InitialAdminProperties pour les deux voies
 * de configuration de l'admin initial.
 */
@Component
public class InitialAdminCreation implements CommandLineRunner
{

    private static final Logger logger = LoggerFactory.getLogger(InitialAdminCreation.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final InitialAdminProperties initialAdminProperties;

    public InitialAdminCreation(UserRepository userRepository, RoleRepository roleRepository,
                                 PasswordEncoder passwordEncoder, InitialAdminProperties initialAdminProperties)
    {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.initialAdminProperties = initialAdminProperties;
    }

    @Override
    public void run(String... args)
    {
        try
        {
            creerRolesSiNecessaire();
            creerAdminInitialSiConfigure();
        }
        catch (Exception e)
        {
            logger.error("Erreur lors de l'initialisation des rôles/de l'administrateur initial", e);
        }
    }

    private void creerRolesSiNecessaire()
    {
        for (Role_Name roleName : Role_Name.values())
        {
            if (roleRepository.findByName(roleName).isEmpty())
            {
                Role newRole = new Role();
                newRole.setName(roleName);
                roleRepository.save(newRole);
                logger.info("Rôle créé en base : " + roleName);
            }
        }
    }

    private void creerAdminInitialSiConfigure()
    {
        if (userRepository.existsByRoleName(Role_Name.ADMIN))
        {
            // Un admin existe déjà (créé lors d'un démarrage précédent, ou via
            // l'assistant web) — rien à faire, dans les deux voies de config.
            return;
        }

        if (!initialAdminProperties.estConfigure())
        {
            // Voie interactive : aucun admin, et rien configuré via .env —
            // l'application démarre volontairement SANS administrateur.
            // L'assistant de première configuration (GET/POST
            // /api/public/setup) prend le relais au premier accès au frontend.
            logger.info("Aucun admin initial configuré (INITIAL_ADMIN_EMAIL/PASSWORD absents) — "
                + "utilisez l'assistant de première configuration à l'accueil de l'application.");
            return;
        }

        // Voie automatisée : email/mot de passe fournis via .env.
        String adminEmail = initialAdminProperties.getEmail();

        if (userRepository.findByEmail(adminEmail).isPresent())
        {
            logger.info("L'administrateur initial ({}) existe déjà.", adminEmail);
            return;
        }

        User admin = new User();
        admin.setNom(valeurOuDefaut(initialAdminProperties.getNom(), "Admin"));
        admin.setPrenom(valeurOuDefaut(initialAdminProperties.getPrenom(), "Principal"));
        admin.setEmail(adminEmail);
        admin.setPassword(passwordEncoder.encode(initialAdminProperties.getPassword()));
        admin.setTelephone(valeurOuDefaut(initialAdminProperties.getTelephone(), "00000000"));
        admin.setActif(true);

        Role adminRole = roleRepository.findByName(Role_Name.ADMIN)
            .orElseGet(() -> {
                Role newRole = new Role();
                newRole.setName(Role_Name.ADMIN);
                return roleRepository.save(newRole);
            });

        Set<Role> roles = new HashSet<>();
        roles.add(adminRole);
        admin.setRoles(roles);

        userRepository.save(admin);

        logger.info("Administrateur initial créé avec succès (voie automatisée, .env) : {}", adminEmail);
    }

    private String valeurOuDefaut(String valeur, String defaut)
    {
        return (valeur != null && !valeur.isBlank()) ? valeur : defaut;
    }
}
