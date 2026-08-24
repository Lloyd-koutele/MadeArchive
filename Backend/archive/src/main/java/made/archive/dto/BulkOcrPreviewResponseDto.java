package made.archive.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Réponse à la Phase 1 du bulk upload same-type.
 *
 * Contient une entrée par fichier soumis, avec le sessionId
 * et les suggestions OCR pour chacun.
 * Le client valide/corrige les métadonnées puis envoie
 * un BulkFinalizeRequestDto pour la Phase 2.
 */
@Data
@Builder
public class BulkOcrPreviewResponseDto
{
    /** Nombre total de fichiers soumis */
    private int total;

    /** Fichiers dont l'OCR a réussi (sessionId disponible) */
    private int success;

    /** Fichiers en erreur (sessionId null dans le preview correspondant) */
    private int failed;

    /**
     * Un OcrPreviewResponseDto par fichier, dans le même ordre que la requête.
     * Si sessionId == null → ce fichier est en erreur (message contient le détail).
     */
    private List<OcrPreviewResponseDto> previews;
}