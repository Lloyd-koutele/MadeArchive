package made.archive.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.service.audit.AuditChainService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chaînage et scellement nocturne du journal d'audit — voir AuditChainService
 * pour le détail et la raison du calcul différé (jamais au moment de l'écriture).
 *
 * Décalé après la purge de rétention (2h, voir DocumentRetentionCleanupScheduler)
 * et avant le contrôle d'intégrité des documents (3h, voir FixityCheckScheduler) —
 * aucune dépendance entre les trois, juste pour étaler la charge nocturne.
 * @EnableScheduling est déjà activé globalement via OcrSessionCleanupScheduler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditChainScheduler
{
    private final AuditChainService auditChainService;

    /**
     * Tous les jours à 2h30 du matin.
     */
    @Scheduled(cron = "0 30 2 * * *")
    public void calculerChainage()
    {
        try
        {
            auditChainService.calculerChainage();
        }
        catch (Exception e)
        {
            log.error("[Chaine-Audit] Erreur lors du chaînage nocturne : {}", e.getMessage(), e);
        }
    }
}
