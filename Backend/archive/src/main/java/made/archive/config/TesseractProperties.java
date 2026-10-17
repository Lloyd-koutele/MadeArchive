package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tesseract")
public class TesseractProperties
{
    private String dataPath;

    /** Langues de reconnaissance, séparées par « + » (ex. fra+eng+ara). Moins de langues = OCR plus rapide. */
    private String langues = "fra+eng+ara";

    /** true : Tika lance lui-même le programme tesseract sur les scans ; false : seul Tess4J (en mémoire) lit les pages. */
    private boolean ocrViaTika = true;

    /**
     * Répertoire ÉCRIVABLE (séparé de dataPath, souvent en lecture seule)
     * où sont construits les dictionnaires "user-words" par TypeDocument,
     * utilisés pour guider Tesseract avec les noms d'attributs du type et
     * les valeurs déjà confirmées lors de dépôts précédents.
     * Voir TesseractDictionaryService.
     */
    private String customDictionaryPath = "/tmp/tesseract-custom-dict";
}