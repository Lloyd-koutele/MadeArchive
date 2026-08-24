package made.archive.dto;

import java.util.List;
import java.util.UUID;

import lombok.Builder;
import lombok.Data;

/**
 * Nœud arborescent (avec enfants imbriqués) — pour parcourir/gérer l'arbre
 * d'une UO d'un seul appel. Voir PhysicalLocationDto pour le DTO plat
 * (création/modification/fiche détail).
 */
@Data
@Builder
public class PhysicalLocationNodeDto
{
    private UUID id;
    private String code;
    private String name;
    private String status;
    private boolean storagePoint;
    private List<PhysicalLocationNodeDto> children;
}
