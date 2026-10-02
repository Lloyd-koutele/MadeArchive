package made.archive.dto;

import lombok.Data;

/** Création (uoId + parentId facultatif) ou modification (code/libellé) d'un nœud. */
@Data
public class PlanClassementNoeudRequestDto
{
    private Long uoId;
    private Long parentId;
    private String code;
    private String libelle;
}
