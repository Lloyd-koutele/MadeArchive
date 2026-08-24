package made.archive.dto;

import lombok.Data;

/**
 * Requête de modification — code/name/description seulement. Le type de
 * nœud (storagePoint) et le statut (ACTIVE/INACTIVE) ont chacun leur propre
 * endpoint/validation dédiée, voir PhysicalLocationService.
 */
@Data
public class PhysicalLocationUpdateDto
{
    private String code;
    private String name;
    private String description;
}
