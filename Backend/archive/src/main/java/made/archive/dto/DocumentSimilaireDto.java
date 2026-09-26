package made.archive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Avertissement "document similaire déjà archivé dans cette UO" — voir
 * DocumentOcrService (détection par texte OCR normalisé) et
 * Document.texteNormaliseSha256 pour le mécanisme.
 *
 * N'existe QUE si le document trouvé est visible par l'utilisateur qui
 * uploade (public de son UO, ou membre du groupe privé) — jamais construit
 * sinon, pour ne révéler ni l'existence ni le titre d'un document privé
 * auquel il n'a pas accès (voir DocumentService.resolveDocumentSiVisible).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentSimilaireDto
{
    private String documentId;
    private String titre;
}
