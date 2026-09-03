package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration de l'export administratif de documents (voir
 * DocumentExportService) — génère un ZIP de documents déchiffrés sur le
 * disque LOCAL du serveur, jamais dans MinIO (voir ExportJob.cheminZip).
 */
@Data
@Component
@ConfigurationProperties(prefix = "document-export")
public class DocumentExportProperties
{
    /** Répertoire où sont écrits les ZIP générés. */
    private String tempDir = "/tmp/madearchive-exports";

    /** Durée de vie d'un export généré avant purge automatique (voir
     *  ExportCleanupScheduler) — volontairement courte : le ZIP contient du
     *  contenu déchiffré, potentiellement des documents privés. */
    private int retentionHours = 48;
}
