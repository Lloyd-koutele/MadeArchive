package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Propriétés générales de l'application — pour l'instant seulement l'URL
 * publique du frontend, nécessaire pour construire le lien encodé dans le QR
 * code d'une attestation (voir service.document.AttestationService). Ce lien
 * pointe vers une page PUBLIQUE du frontend (pas vers le backend directement)
 * pour offrir une visionneuse + un bouton téléchargement, comme le reste de
 * l'application.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties
{
    private String frontendUrl;
}
