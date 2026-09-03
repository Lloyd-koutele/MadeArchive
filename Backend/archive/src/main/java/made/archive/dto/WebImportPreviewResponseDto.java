package made.archive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Résultat de la découverte d'une URL — avant tout téléchargement.
 * type = "FICHIER_DIRECT" (l'URL pointe directement sur un fichier) ou
 * "PAGE_WEB" (l'URL est une page HTML dont les liens vers des documents
 * ont été extraits).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebImportPreviewResponseDto
{
    private String sourceUrl;
    private String type;
    private List<WebImportFileDto> fichiers;
}
