package made.archive.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/** Résultat d'une vérification de la chaîne du journal d'audit — voir
 *  service.audit.AuditChainService.verifierChaine. */
@Data
@Builder
public class ChaineAuditVerificationDto
{
    private boolean chaineIntacte;
    private long     nombreEntreesChainees;

    /** Ruptures détectées, visibles par l'appelant — un ADMIN_UO ne voit que
     *  celles de son périmètre (voir AuditLogController), même si la chaîne
     *  entière a été recalculée pour les trouver (le chaînage traverse toutes
     *  les UO, une vérification partielle par UO n'aurait pas de sens). */
    private List<ChaineAuditRuptureDto> ruptures;

    /** true si des ruptures existent AILLEURS que dans le périmètre visible de
     *  l'appelant (ADMIN_UO) — signalé sans détail, pour qu'un ADMIN_UO sache
     *  que "ma branche est intacte" ne veut pas dire "tout le journal est intact". */
    private boolean rupturesHorsPerimetre;

    /** Anomalies qui ne se rattachent à aucune entrée : clé de chaînage absente ou différente, registre des
     *  scellements introuvable, altéré ou incomplet. Détaillées aux seuls ADMIN globaux. */
    private List<String> anomaliesGlobales;

    private Instant dernierScellementDate;
    private Long    dernierScellementEntryId;
}
