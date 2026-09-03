package made.archive.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import made.archive.entite.ExportJobStatus;

@Data
@AllArgsConstructor
public class ExportJobStatutDto
{
    private UUID id;
    private ExportJobStatus statut;
    private int documentsTotal;
    private int documentsTraites;
    private int documentsEnEchec;
    private LocalDateTime createAt;
    private LocalDateTime completedAt;
    private LocalDateTime expireAt;
}
