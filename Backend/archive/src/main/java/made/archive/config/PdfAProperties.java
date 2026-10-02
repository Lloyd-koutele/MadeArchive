package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Conversion et vérification PDF/A — voir PdfAConversionService.
 */
@Data
@Component
@ConfigurationProperties(prefix = "pdfa")
public class PdfAProperties
{
    /**
     * Partie de la norme ISO 19005 visée (niveau de conformité toujours "b") :
     * 2 → PDF/A-2b, 3 → PDF/A-3b. Seules ces deux valeurs sont acceptées.
     */
    private int partie = 3;

    /** Exécutable Ghostscript — "gs" s'il est dans le PATH (cas du Dockerfile). */
    private String ghostscriptChemin = "gs";

    private int ghostscriptTimeoutSecondes = 120;

    /**
     * Part minimale des mots du PDF source qu'on doit retrouver dans le PDF/A
     * produit par Ghostscript (0.90 = 90 %) — en dessous, la conversion a
     * dégradé le texte (police mal substituée, glyphes perdus) et le PDF/A est
     * refusé. Sans effet sur un PDF sans couche texte (scan pur).
     */
    private double seuilFideliteTexte = 0.90;

    public String getLibelleProfil()
    {
        return "PDF/A-" + partie + "b";
    }
}
