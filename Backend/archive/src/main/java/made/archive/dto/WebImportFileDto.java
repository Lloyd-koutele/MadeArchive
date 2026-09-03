package made.archive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Un fichier découvert par WebImportService.previewer — pas encore téléchargé. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebImportFileDto
{
    private String nomFichier;
    private String url;
}
