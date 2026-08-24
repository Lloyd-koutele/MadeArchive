package made.archive.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
public class SearchResultItemDto
{
    private UUID documentId;
    private String titre;
    private String typeDocument;
    private String access;
    private String status;
    private LocalDate retentionUntil;

    /** "Version 1", "Version 2"... ou "Final". null si jamais versionné. */
    private String versionLabel;
}