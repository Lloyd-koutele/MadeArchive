package made.archive.dto;

import java.util.List;
import java.util.UUID;

import lombok.Data;

/**
 * Nœud d'arborescence pour un envoi en un seul appel — voir
 * PhysicalLocationService.creerArborescence (création) et
 * mettreAJourArborescence (modification). Récursif : chaque nœud porte ses
 * propres enfants, construits côté client (brouillon local) AVANT tout envoi
 * au serveur, puis appliqués d'un coup, dans l'ordre, en une seule
 * transaction.
 *
 * id : null pour un nouveau nœud à créer ici (création ET modification —
 * on peut toujours ajouter de nouveaux descendants) ; non-null pour un nœud
 * EXISTANT à renommer (modification uniquement, jamais en création). Ignoré
 * à la racine d'un appel de modification (le nœud racine est déjà désigné
 * par l'id du chemin de l'URL) — pertinent seulement pour les enfants.
 * storagePoint est ignoré pour un nœud existant (id != null) : le type ne se
 * change pas via cet appel, voir PhysicalLocationService.changerTypeStockage.
 */
@Data
public class PhysicalLocationTreeNodeDto
{
    private UUID id;
    private String name;
    private String description;
    private boolean storagePoint;

    /** Nombre maximal de documents — uniquement pour un NOUVEAU nœud
     *  storagePoint=true (id == null). Ignoré pour un nœud existant : voir
     *  PhysicalLocationService.definirCapacite pour modifier celle d'un nœud
     *  déjà en base. */
    private Integer capaciteMax;

    /** LIBRE (défaut si absent), TYPE_UNIQUE ou DOSSIER — voir
     *  LocationModeContrainte. Uniquement pour un NOUVEAU nœud
     *  storagePoint=true (id == null) ; ignoré pour un nœud existant, voir
     *  PhysicalLocationService.definirContrainte. */
    private String modeContrainte;

    /** Renseigné seulement si modeContrainte=TYPE_UNIQUE, pour un nouveau nœud. */
    private Long typeDocumentId;

    /** Renseigné seulement si modeContrainte=DOSSIER, pour un nouveau nœud. */
    private Long dossierId;

    private List<PhysicalLocationTreeNodeDto> children;
}
