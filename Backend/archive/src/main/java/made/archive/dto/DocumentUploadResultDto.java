package made.archive.dto;

import lombok.Data;
import made.archive.entite.DocumentStatus;

import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DocumentUploadResultDto
{
    private UUID documentId;
    private DocumentStatus status;
    private String originalSha256;
    private String pdfaSha256;
    private String storageKey;
    private Long version;
    private String versionLabel;
    private Map<String, String> metaDataSuggestions;
}