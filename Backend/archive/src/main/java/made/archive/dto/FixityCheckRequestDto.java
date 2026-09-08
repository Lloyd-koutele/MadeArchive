package made.archive.dto;

import java.util.List;

import lombok.Data;

/**
 * Requête de déclenchement MANUEL du contrôle d'intégrité (fixity check) —
 * voir FixityCheckTriggerService. Trois périmètres mutuellement exclusifs :
 *
 *   scope = "TYPES" → typeDocumentIds (un ou plusieurs)
 *   scope = "UO"    → uoIds (un ou plusieurs)
 *   scope = "TOUT"  → aucun des deux, réservé à ROLE_ADMIN
 */
@Data
public class FixityCheckRequestDto
{
    private String scope;
    private List<Long> typeDocumentIds;
    private List<Long> uoIds;
}
