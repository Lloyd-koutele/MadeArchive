package made.archive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Import d'un dossier Google Drive par l'API officielle (Drive API v3) — voir service.importweb.GoogleDriveService.
 *
 * L'API liste les fichiers du dossier, puis chacun est téléchargé séparément : aucune archive n'est téléchargée ni
 * décompressée. Seuls les dossiers partagés « tous les utilisateurs disposant du lien » sont lisibles avec une clé
 * d'API ; la clé n'ouvre AUCUN fichier privé.
 *
 * Sans clé (vide), l'import de dossier Drive reste désactivé — les autres imports par lien ne sont pas touchés.
 */
@Data
@Component
@ConfigurationProperties(prefix = "google-drive")
public class GoogleDriveProperties
{
    /** Clé d'API Google (Google Cloud Console, API « Google Drive » activée). Jamais renvoyée au navigateur. */
    private String apiKey = "";

    /** Adresse de l'API — ne change que pour les tests. */
    private String apiBaseUrl = "https://www.googleapis.com";

    /** Nombre maximal de fichiers retenus pour un dossier (sous-dossiers compris). */
    private int maxFichiers = 100;

    /** Profondeur maximale de descente dans les sous-dossiers (0 = le dossier seul). */
    private int maxProfondeur = 3;

    /** Nombre maximal d'appels à l'API pour lister UN dossier — borne le temps et le quota consommés. */
    private int maxAppels = 30;

    public boolean active()
    {
        return apiKey != null && !apiKey.isBlank();
    }
}
