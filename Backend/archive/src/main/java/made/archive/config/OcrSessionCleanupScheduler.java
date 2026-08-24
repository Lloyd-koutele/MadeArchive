package made.archive.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.service.document.OcrSessionCache;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tâche planifiée pour nettoyer les sessions OCR expirées
 * S'exécute automatiquement toutes les minutes
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class OcrSessionCleanupScheduler
{
    private final OcrSessionCache ocrSessionCache;

    /**
     * Nettoie les sessions expirées toutes les minutes
     */
    @Scheduled(fixedDelay = 60000)  // 1 minute = 60000 ms
    public void cleanupExpiredSessions()
    {
        try
        {
            ocrSessionCache.cleanup();
            log.debug("[OcrCleanup] Nettoyage des sessions expirées effectué");
        }
        catch (Exception e)
        {
            log.error("[OcrCleanup] Erreur lors du nettoyage : {}", e.getMessage());
        }
    }
}