package made.archive.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Réponse du serveur pour la PHASE 1 (OCR Preview)
 * 
 * Contient :
 * - sessionId : UUID pour la phase 2
 * - metaDataSuggestions : Pré-remplissages proposés
 * - message : Message optionnel ("Premier document", "Pas de texte", etc.)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OcrPreviewResponseDto
{
    private String sessionId;

    /**
     * Nom du fichier traité — utile côté client pour recomposer la liste sans
     * dépendre de l'ordre d'un File[] (notamment pour l'import via lien, où les
     * fichiers n'existent jamais côté navigateur).
     */
    private String nomFichier;

    private Map<String, String> metaDataSuggestions;
    private String message;

    /**
     * Absent (null, omis du JSON — voir @JsonInclude) si aucun document
     * similaire trouvé, OU si l'utilisateur n'y a pas accès (voir
     * DocumentSimilaireDto). Purement informatif : n'empêche jamais
     * l'archivage, contrairement à un vrai doublon (originalSha256).
     */
    private DocumentSimilaireDto documentSimilaire;

    /**
     * Absent (null, omis du JSON) si non pertinent (pas un tableur mis à
     * l'échelle sur une page) ou si la mesure a échoué. Sinon, la plus
     * petite taille de police (points) trouvée dans le PDF converti — au
     * client de décider du seuil d'alerte (8pt, voir ImportDocuments.tsx).
     * Purement informatif : n'empêche jamais l'archivage.
     */
    private Double policeMinPt;

    // Optionnel : pour debug
    private String extractedTextPreview;
}