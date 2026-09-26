package made.archive.dto;

import java.util.UUID;

import lombok.Data;

/**
 * Requête de création d'un emplacement et de sa descendance en un seul appel
 * — voir PhysicalLocationService.creerArborescence. Toujours UNE SEULE
 * racine (pas plusieurs) : pour créer plusieurs emplacements indépendants,
 * on répète l'action bouton par bouton, chacune posant sa propre racine +
 * descendance. parentId : où accrocher cette racine (null = racine de l'UO),
 * même sémantique que PhysicalLocationCreateDto.parentId.
 */
@Data
public class PhysicalLocationArborescenceRequestDto
{
    private Long uniteOrganisationnelleId;
    private UUID parentId;
    private PhysicalLocationTreeNodeDto node;
}
