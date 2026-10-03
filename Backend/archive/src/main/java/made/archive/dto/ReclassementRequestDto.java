package made.archive.dto;

import java.util.List;
import java.util.UUID;

import lombok.Data;

/**
 * Reclassement d'un document archivé par erreur dans le mauvais type — voir DocumentService.reclasser.
 * Le fichier, ses empreintes, sa signature et son horodatage ne changent JAMAIS (ils portent sur le fichier,
 * pas sur son classement) ; ne s'applique qu'à la version ouverte, pas à toute la chaîne de versions.
 */
@Data
public class ReclassementRequestDto
{
    /** Nouveau type (même UO que le document) — null ou identique à l'actuel : le type ne change pas. */
    private Long typeDocumentId;

    /** Valeurs des métadonnées du NOUVEAU type (par libellé de champ) — ignorées si le type ne change pas. */
    private List<DataTypeDto> metaData;

    /** true = appliquer planClassementNoeudId (null = revenir à l'activité par défaut du type). */
    private boolean modifierActivite;
    private Long planClassementNoeudId;

    /** true = appliquer dossierId (null = détacher du dossier). Même logique que modifierDossierDocument. */
    private boolean modifierDossier;
    private Long dossierId;
    private boolean fusionnerGroupes;

    /** true = appliquer physicalLocationId (null = retirer l'emplacement). */
    private boolean modifierEmplacement;
    private UUID physicalLocationId;
}
