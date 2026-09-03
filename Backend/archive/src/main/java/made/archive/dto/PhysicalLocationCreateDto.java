package made.archive.dto;

import java.util.UUID;

import lombok.Data;

/**
 * Requête de création — voir PhysicalLocationService.creer.
 * uniteOrganisationnelleId est toujours obligatoire (même pour un enfant) —
 * le service vérifie qu'il correspond à celui du parent si parentId est fourni.
 */
@Data
public class PhysicalLocationCreateDto
{
    private String name;
    private String description;
    private boolean storagePoint;
    private UUID parentId;
    private Long uniteOrganisationnelleId;
}
