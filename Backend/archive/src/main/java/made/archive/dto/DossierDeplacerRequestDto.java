package made.archive.dto;

import lombok.Data;

/**
 * Requête de déplacement d'un dossier — voir DossierService.deplacerDossier.
 */
@Data
public class DossierDeplacerRequestDto
{
    /** null = déplacer vers la racine de l'UO. */
    private Long nouveauParentId;
}
