package made.archive.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

import made.archive.entite.AuditAction;

/** Une entrée du journal d'audit dont l'empreinte chaînée ne correspond plus à
 *  ce qu'elle devrait être — voir service.audit.AuditChainService.verifierChaine. */
@Data
@Builder
public class ChaineAuditRuptureDto
{
    private Long id;
    private Instant horodatage;
    private Long uoId;
    private AuditAction action;
    private String description;
}
