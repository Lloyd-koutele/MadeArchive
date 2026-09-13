package made.archive.security;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Adresse IP réelle du client derrière Traefik — extraite via
 * RequestContextHolder plutôt que reçue en paramètre, pour être appelable
 * depuis n'importe quel service sans faire remonter HttpServletRequest dans
 * toute la chaîne d'appel.
 *
 * Logique auparavant dupliquée en privé dans AuditLogService.adresseIpCourante() ;
 * extraite ici pour être réutilisée telle quelle par LoginAttemptService (blocage
 * après échecs de connexion répétés — voir sa Javadoc), les deux ayant strictement
 * besoin de la même adresse.
 *
 * X-Forwarded-For : Traefik est le seul point d'entrée HTTPS (voir docker-compose.yml,
 * app/frontend n'exposent aucun port), donc cet en-tête est fiable ici — jamais un
 * client externe qui le forgerait directement contre l'application.
 */
@Component
public class ClientIpResolver
{
    /** @return l'adresse IP du client, ou null si aucune requête HTTP n'est en cours (ex. tâche planifiée). */
    public String resolve()
    {
        try
        {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;

            HttpServletRequest request = attrs.getRequest();
            String transmis = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(transmis))
            {
                return transmis.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
