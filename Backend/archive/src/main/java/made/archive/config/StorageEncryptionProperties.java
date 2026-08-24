package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Clé de chiffrement au repos (AES-256-GCM) des PDF/A archivés dans MinIO.
 * Archivage à valeur probante : le PDF/A n'est JAMAIS écrit en clair dans le
 * stockage objet — voir made.archive.security.DocumentEncryptionService.
 */
@Data
@Component
@ConfigurationProperties(prefix = "storage.encryption")
public class StorageEncryptionProperties
{
    /** Clé AES-256 encodée en Base64 (32 octets décodés). */
    private String key;
}
