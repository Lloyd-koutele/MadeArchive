package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Conteneur Gotenberg (LibreOffice packagé en service HTTP persistant) —
 * remplace l'ancien pool LibreOffice in-process. Voir docker-compose.yml.
 */
@Data
@Component
@ConfigurationProperties(prefix = "gotenberg")
public class GotenbergProperties
{
    private String baseUrl;
    private int timeoutSeconds = 60;
}
