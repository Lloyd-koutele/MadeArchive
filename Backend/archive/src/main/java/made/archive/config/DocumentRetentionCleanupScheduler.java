package made.archive.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.service.document.DocumentRetentionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tâche planifiée pour la fin de vie des documents — met d'abord à la
 * corbeille ceux dont la rétention légale vient d'être atteinte
 * (purgeExpiredDocuments), puis purge réellement ceux dont le délai de grâce
 * de la corbeille est écoulé sans restauration (purgeDocumentsCorbeille),
 * qu'ils y soient arrivés ainsi ou manuellement (voir DocumentRetentionService).
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
