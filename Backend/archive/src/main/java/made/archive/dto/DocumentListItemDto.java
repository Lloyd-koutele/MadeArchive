package made.archive.dto;
 
import lombok.Builder;
import lombok.Data;
 
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
 
@Data
@Builder
public class DocumentListItemDto
{
    private UUID          documentId;
    private String        titre;
    private Long          typeDocumentId;
    private String        typeDocumentNom;
    private String        status;           // ACTIVE, PENDING, CORRUPTED...
    private String        access;           // PUBLIC, PRIVE
    private LocalDate     retentionUntil;
    private LocalDateTime createAt;

    /**
     * "Version 1", "Version 2"... ou "Final" pour la version actuelle d'une
     * chaîne. null si ce document n'a jamais été versionné (pas de badge).
     */
    private String versionLabel;
}
