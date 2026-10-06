package made.archive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Copie des preuves de chaque document dans un bucket MinIO SÉPARÉ, verrouillé en écriture (Object Lock) —
 * second dépôt indépendant de PostgreSQL : un attaquant qui n'a que la base ne peut pas y toucher, et même avec
 * l'accès MinIO il ne peut ni modifier ni supprimer un manifeste avant la fin de sa période de rétention.
 *
 * Identifiants distincts recommandés (preuves.manifeste.access-key / secret-key) : sans eux, ceux du stockage des
 * documents sont réutilisés et l'indépendance se limite au verrou d'objet.
 */
@Data
@Component
@ConfigurationProperties(prefix = "preuves.manifeste")
public class PreuvesManifesteProperties
{
    private boolean actif = true;

    /** Vide = "<minio.bucket>-preuves". */
    private String bucket;

    private String accessKey;
    private String secretKey;

    /** Durée du verrou (jours). Les manifestes font quelques centaines d'octets. */
    private int retentionJours = 3650;

    /** GOVERNANCE (contournable seulement par une permission explicite) ou COMPLIANCE (irréversible, même pour root). */
    private String mode = "GOVERNANCE";
}
