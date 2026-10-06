package made.archive.dto;

import lombok.Data;

/** Création (uoId + parentId facultatif) ou modification (libellé) d'un nœud — le code est généré par le serveur. */
@Data
public class PlanClassementNoeudRequestDto
{
    private Long uoId;
    private Long parentId;
    private String libelle;
}
