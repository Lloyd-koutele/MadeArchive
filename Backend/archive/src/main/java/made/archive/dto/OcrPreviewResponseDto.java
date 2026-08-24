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
     * dépendre de l'ordre d'un File[] (notamment pour l'import FTP, où les
     * fichiers n'existent jamais côté navigateur).
     */
    private String nomFichier;

    private Map<String, String> metaDataSuggestions;
    private String message;
    
    // Optionnel : pour debug
    private String extractedTextPreview;
}