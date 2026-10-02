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
    private String name;
    private String description;
    private String status;
    private boolean storagePoint;

    /** Nombre maximal de documents (uniquement significatif si storagePoint=true) — null = illimité. */
    private Integer capaciteMax;

    /** Nombre de documents actuellement rattachés (vivants, hors DELETED) — voir PhysicalLocationService.toDto. */
    private long nombreDocuments;

    /** LIBRE, TYPE_UNIQUE ou DOSSIER — voir LocationModeContrainte. Sans effet si storagePoint=false. */
    private String modeContrainte;

    /** Renseigné seulement si modeContrainte=TYPE_UNIQUE. */
    private Long typeDocumentAccepteId;
    private String typeDocumentAccepteNom;

    /** Renseigné seulement si modeContrainte=DOSSIER. */
    private Long dossierId;
    private String dossierNom;

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
