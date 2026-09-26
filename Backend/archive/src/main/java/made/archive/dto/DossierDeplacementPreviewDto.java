package made.archive.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import made.archive.entite.User;

/**
 * Aperçu (dry-run) d'un déplacement de dossier — à afficher dans un modal
 * d'alerte AVANT de confirmer le déplacement effectif (voir
 * DossierService.deplacerDossier), jamais calculé après coup.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DossierDeplacementPreviewDto
{
    /**
     * true si ce dossier est actuellement PUBLIC et que le nouveau parent est
     * PRIVÉ — le déplacement le forcera PRIVÉ (nouveau groupe hérité du
     * parent), même invariant qu'à la création (voir Dossier.parent).
     */
    private boolean deviendraPrive;

    /**
     * true si ce dossier ET le nouveau parent sont TOUS DEUX déjà PRIVÉS avec
     * des groupes d'accès divergents — le dossier déplacé garde son propre
     * groupe intact, mais membresDivergents ci-dessous seront AJOUTÉS au
     * groupe du nouveau parent (et de ses propres ancêtres privés) pour
     * préserver l'invariant de navigabilité.
     */
    private boolean divergenceGroupes;

    /** Membres du groupe du dossier déplacé absents du groupe du nouveau parent. */
    private List<User> membresDivergents;
}
