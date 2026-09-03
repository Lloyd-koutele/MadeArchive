package made.archive.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.service.document.DocumentRetentionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tâche planifiée pour purger les documents en fin de vie — soit par fin de
 * rétention, soit par délai de grâce de 3 jours écoulé pour un document en
 * corbeille (voir DocumentRetentionService).
 *
 * Une fois par jour suffit : les deux échéances sont des dates (granularité jour).
 * @EnableScheduling est déjà activé globalement via OcrSessionCleanupScheduler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentRetentionCleanupScheduler
{
    private final DocumentRetentionService documentRetentionService;

    /**
     * Tous les jours à 2h du matin.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void purgeExpiredDocuments()
    {
        try
        {
            documentRetentionService.purgeExpiredDocuments();
        }
        catch (Exception e)
        {
            log.error("[Retention] Erreur lors de la purge des documents expirés : {}",
                e.getMessage(), e);
        }

        try
        {
            documentRetentionService.purgeDocumentsCorbeille();
        }
        catch (Exception e)
        {
            log.error("[Retention] Erreur lors de la purge de la corbeille : {}",
                e.getMessage(), e);
        }
    }
}
