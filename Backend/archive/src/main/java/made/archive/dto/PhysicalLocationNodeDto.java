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
    private String name;
    private String status;
    private boolean storagePoint;

    /** Nombre maximal de documents (uniquement significatif si storagePoint=true) — null = illimité. */
    private Integer capaciteMax;

    /** Nombre de documents actuellement rattachés (vivants, hors DELETED) — voir PhysicalLocationService.getArbre. */
    private long nombreDocuments;

    /** LIBRE, TYPE_UNIQUE ou DOSSIER — voir LocationModeContrainte. Sans effet si storagePoint=false. */
    private String modeContrainte;

    /** Renseigné seulement si modeContrainte=TYPE_UNIQUE. */
    private Long typeDocumentAccepteId;
    private String typeDocumentAccepteNom;

    /** Renseigné seulement si modeContrainte=DOSSIER. */
    private Long dossierId;
    private String dossierNom;

    private List<PhysicalLocationNodeDto> children;
}
