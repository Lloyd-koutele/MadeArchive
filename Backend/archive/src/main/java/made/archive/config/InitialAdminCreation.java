package made.archive.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

import made.archive.repository.UserRepository;
import made.archive.repository.RoleRepository; // 💡 Ne pas oublier l'import
import made.archive.entite.User;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;

@Component
public class InitialAdminCreation implements CommandLineRunner 
{

    private static final Logger logger = LoggerFactory.getLogger(InitialAdminCreation.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository; // 💡 Étape 1 : Ajouter le Repository des rôles
    private final PasswordEncoder passwordEncoder;

    // 💡 Étape 2 : L'ajouter au constructeur pour l'injection Spring
    public InitialAdminCreation(UserRepository userRepository, RoleRepository roleRepository, PasswordEncoder passwordEncoder) 
    {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) 
    {
        try 
        {
            createInitialAdminIfNeeded();
        }
         catch (Exception e) 
        {
            logger.error("Erreur lors de la création de l'administrateur initial", e);
        }
    }

    private void createInitialAdminIfNeeded() 
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
        String adminEmail = "koutelemarvinlloyd@gmail.com";

        if (userRepository.findByEmail(adminEmail).isEmpty()) 
        {
            User admin = new User(); 
            admin.setNom("Marvin");
            admin.setPrenom("Lloyd");
            admin.setEmail(adminEmail);
            admin.setPassword(passwordEncoder.encode("Marvic&21"));
            admin.setTelephone("778370157");
            admin.setActif(true);

            // 💡 Étape 3 : Récupérer le rôle existant ou le créer proprement en base s'il est absent
            Role adminRole = roleRepository.findByName(Role_Name.ADMIN)
                .orElseGet(() -> {
                    Role newRole = new Role();
                    newRole.setName(Role_Name.ADMIN);
                    return roleRepository.save(newRole); // Sauvegardé en BDD -> il a maintenant un ID !
                });
            
            Set<Role> roles = new HashSet<>();
            roles.add(adminRole);
            admin.setRoles(roles);

            // 💡 Étape 4 : Maintenant, la sauvegarde de l'user fonctionnera parfaitement !
            userRepository.save(admin);
            
            logger.info("Administrateur initial créé avec succès\n"
                    + "Le login est : koutelemarvinlloyd@gmail.com\n"
                    + "Le mot de passe est : Marvic&21");
        } 
        else 
        {
            logger.info("L'administrateur initial existe déjà\n"
                    + "Le login est : koutelemarvinlloyd@gmail.com\n"
                    + "Le mot de passe est : Marvic&21");
        }
    }
}