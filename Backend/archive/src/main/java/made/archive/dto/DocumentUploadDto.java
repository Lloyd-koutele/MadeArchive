package made.archive.dto;

import lombok.Data;
import made.archive.entite.IntegrityLevel;
import made.archive.entite.TypeAccess;

import java.util.List;
import java.util.UUID;

@Data
public class DocumentUploadDto
{
    private String titre;
    private TypeAccess access;
    private Long typeDocumentId;
    private UUID uploadedById;
    private IntegrityLevel integrityLevel;

    // Optionnel — l'uploadeur est toujours ajouté automatiquement
    private List<UUID> groupeMembresIds;

    // Optionnel — rattache le document à un projet (dossier/affaire) existant
    private Long projetId;

    // Optionnel — ce document devient la version suivante de ce document
    // existant (doit être la version actuelle de sa chaîne, même UO, même type)
    private UUID documentPrecedentId;

    // Optionnel — emplacement physique de l'original papier (voir
    // PhysicalLocation). Doit être un point de stockage ACTIF de la même UO,
    // validé par PhysicalLocationService.resolvePourRattachement.
    private UUID physicalLocationId;
}