package made.archive.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Requête Phase 1 du bulk upload "même type" depuis un lien web — après que
 * l'utilisateur a confirmé, sur l'aperçu retourné par WebImportService.previewer,
 * la liste des fichiers à réellement télécharger et traiter.
 */
@Data
public class WebImportOcrRequestDto
{
    /** URLs absolues confirmées par l'utilisateur (issues de l'aperçu). */
    private List<String> fichiersUrls;

    private Long typeDocumentId;
    private UUID uploadedById;
}
