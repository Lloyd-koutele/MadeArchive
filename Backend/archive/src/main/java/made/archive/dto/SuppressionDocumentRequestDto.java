package made.archive.dto;

import lombok.Data;
import made.archive.entite.MotifSuppression;

/** Motif d'une suppression (mise en corbeille ou suppression définitive) saisi par l'éditeur. */
@Data
public class SuppressionDocumentRequestDto
{
    private MotifSuppression motif;
    /** Obligatoire si motif = AUTRE. */
    private String commentaire;
}
