package made.archive.dto;

import lombok.Data;

/**
 * Requête d'aperçu (découverte, sans téléchargement) pour l'import via lien
 * web — voir WebImportService.previewer.
 */
@Data
public class WebImportPreviewRequestDto
{
    private String url;
}
