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

    // Origines CORS supplémentaires (en plus de frontendUrl et des ports Vite
    // locaux, voir SecurityConfig), séparées par des virgules — pour tester
    // depuis un autre appareil du réseau local (ex: téléphone via une IP:port
    // qui n'est pas le domaine canonique de frontendUrl) sans avoir à
    // reconstruire l'image à chaque fois. Distinct de frontendUrl : celui-ci
    // reste l'URL canonique unique utilisée dans les liens de QR code
    // (AttestationService) — jamais une liste.
    private String corsAdditionalOrigins;
}
