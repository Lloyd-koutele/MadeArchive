package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Admin initial — deux voies de configuration coexistent (voir
 * InitialAdminCreation et service.user.SetupService) :
 *   - automatisée : ces propriétés renseignées (.env) → créé au démarrage,
 *     sans intervention humaine, adapté à un déploiement scripté ;
 *   - interactive : ces propriétés absentes → l'application démarre SANS
 *     admin, l'assistant web de première configuration (endpoint
 *     /api/public/setup) prend le relais au premier accès.
 *
 * Remplace l'admin auparavant codé EN DUR dans InitialAdminCreation (email et
 * mot de passe réels d'un développeur, dans le code source) — un vrai risque
 * de sécurité pour une image destinée à être redistribuée : n'importe qui
 * récupérant l'image connaissait les identifiants admin de toute
 * installation utilisant les valeurs par défaut.
 */
@Data
@Component
@ConfigurationProperties(prefix = "initial-admin")
public class InitialAdminProperties
{
    private String email;
    private String password;
    private String nom;
    private String prenom;
    private String telephone;

    public boolean estConfigure()
    {
        return email != null && !email.isBlank() && password != null && !password.isBlank();
    }
}
