package made.archive.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO plat — création, modification et réponse détail d'un PhysicalLocation.
 * Voir PhysicalLocationNodeDto pour la représentation arborescente (browsing).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalLocationDto
{
    private UUID id;
    private String code;
    private String name;
    private String description;
    private String status;
    private boolean storagePoint;
    private UUID parentId;
    private Long uniteOrganisationnelleId;

    // Pratique côté client : évite de reconstruire le chemin depuis l'arbre
    // juste pour l'afficher (ex. sur la fiche d'un document).
    private String cheminComplet;

    private LocalDateTime createdAt;
    private String createdByNom;
    private LocalDateTime updatedAt;
    private String updatedByNom;
}
