package made.archive.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un maillon de l'historique de versions d'un document (voir Document.version,
 * documentPrecedent, documentRacine, derniereVersion).
 */
@Data
@Builder
public class DocumentVersionDto
{
    private UUID          documentId;
    private String        titre;
    private long           version;

    /** "Version 1", "Version 2"... ou "Final" pour la version actuelle. */
    private String        versionLabel;

    private boolean        estVersionActuelle;
    private LocalDateTime createAt;
    private String        uploadedByNom;
}
