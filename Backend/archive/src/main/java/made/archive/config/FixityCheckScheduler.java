package made.archive.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.service.document.FixityCheckService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Vérification de routine de l'intégrité des documents archivés : retélécharge
 * chaque PDF/A depuis MinIO, recalcule son SHA-256 et le compare à
 * Document.pdfaSha256 (voir FixityCheckService — la comparaison est stable,
 * aucune reconversion n'est faite, donc pas de faux-positif lié à une date).
 *
 * Une fois par jour suffit, décalée après la purge de rétention (2h) pour ne
 * pas cogner MinIO/la base en même temps.
 * @EnableScheduling est déjà activé globalement via OcrSessionCleanupScheduler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FixityCheckScheduler
{
    private final FixityCheckService fixityCheckService;

    /**
     * Tous les jours à 3h du matin.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void verifyAllDocuments()
    {
        try
        {
            fixityCheckService.verifyAllDocuments();
            log.info("[Fixity] Vérification de routine terminée");
        }
        catch (Exception e)
        {
            log.error("[Fixity] Erreur lors de la vérification de routine : {}", e.getMessage(), e);
        }
    }
}
