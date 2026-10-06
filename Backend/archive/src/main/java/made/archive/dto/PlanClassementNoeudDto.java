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
    /** Documents dont l'activité effective est exactement ce nœud (la leur, ou celle de leur type). */
    private long nbDocuments;
    /** true si ce nœud OU l'un de ses descendants a des documents : il n'est plus modifiable (libellé, position,
     *  suppression) — le code et le libellé figurent dans les exports et procès-verbaux déjà produits. */
    private boolean verrouille;
    private List<PlanClassementNoeudDto> children;
}
