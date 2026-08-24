package made.archive.dto;

import lombok.Builder;
import lombok.Data;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class AuditLogDto
{
    private Long id;
    private Instant horodatage;

    private UUID   acteurId;
    private String acteurEmail;
    private String acteurRole;
    private String adresseIp;

    private AuditAction action;
    private AuditCible  cibleType;
    private String      cibleId;
    private Long         uoId;

    private String  description;
    private boolean succes;
    private String  details;
}
