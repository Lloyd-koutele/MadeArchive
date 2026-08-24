package made.archive.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Requête de création de projet.
 */
@Data
public class ProjetDto
{
    private String nom;
    private String description;
    private Long uoId;

    /** Types de documents attendus (informatif — voir Projet.typesDocumentsAttendus). Optionnel. */
    private List<Long> typeDocumentIds;

    /** "PUBLIC" (défaut si absent) ou "PRIVE". */
    private String access;

    /**
     * Membres initiaux du groupe d'accès si access == "PRIVE" — le créateur y
     * est ajouté automatiquement, inutile de l'inclure ici (voir ProjetService).
     */
    private List<UUID> groupeMembresIds;
}
