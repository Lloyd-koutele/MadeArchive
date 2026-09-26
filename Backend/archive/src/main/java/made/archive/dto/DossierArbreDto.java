package made.archive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nœud minimal pour construire l'arbre complet des dossiers d'une UO côté
 * client (un seul aller-retour, comme PhysicalLocationNodeDto pour les
 * emplacements physiques) — utilisé par le sélecteur de dossier cible à
 * l'archivage (voir DossierService.getArbreDossiers), jamais pour l'affichage
 * détaillé d'un dossier (pas de creePar/createAt/access ici, voir DossierDto).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DossierArbreDto
{
    private Long id;
    private String nom;
    /** null = dossier racine de l'UO. */
    private Long parentId;
}
