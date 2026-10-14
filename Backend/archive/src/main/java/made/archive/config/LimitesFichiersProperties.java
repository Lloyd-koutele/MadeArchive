package made.archive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import lombok.Data;

/**
 * Limites de taille et de nombre de fichiers, UNE SEULE SOURCE pour tous les modes de dépôt : fichiers déposés depuis
 * l'ordinateur (réglages multipart de Spring et de Tomcat, qui en sont dérivés dans application.properties) ET import
 * par lien web ou dossier Google Drive (WebImportService, GoogleDriveService). Un import par lien n'a donc pas de limite
 * plus étroite qu'un dépôt local.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.limites")
public class LimitesFichiersProperties
{
    /** Taille maximale d'UN fichier. */
    private DataSize maxFichier = DataSize.ofMegabytes(101);
    /** Taille totale maximale d'UNE demande (tous les fichiers du lot). */
    private DataSize maxTotal = DataSize.ofMegabytes(2000);
    /** Nombre maximal de fichiers d'UNE demande. */
    private int maxFichiers = 10000;

    public long maxFichierOctets()
    {
        return maxFichier.toBytes();
    }

    public long maxTotalOctets()
    {
        return maxTotal.toBytes();
    }
}
