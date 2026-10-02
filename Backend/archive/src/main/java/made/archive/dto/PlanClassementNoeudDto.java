package made.archive.dto;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/** Nœud du plan de classement d'une UO, avec ses enfants (arbre complet en une réponse). */
@Data
@Builder
public class PlanClassementNoeudDto
{
    private Long id;
    private String code;
    private String libelle;
    private Long parentId;
    /** Nombre de types de documents directement rattachés à ce nœud (hors descendants). */
    private long nbTypes;
    private List<PlanClassementNoeudDto> children;
}
