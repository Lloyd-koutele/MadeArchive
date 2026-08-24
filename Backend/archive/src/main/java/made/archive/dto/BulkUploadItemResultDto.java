package made.archive.dto;

import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data
@Builder
public class BulkUploadItemResultDto
{
    private String nomFichier;
    private String typeDocument;
    private String status; // "SUCCESS" ou "FAILED"
    private UUID documentId; // null si FAILED
    private String erreur;   // null si SUCCESS
}