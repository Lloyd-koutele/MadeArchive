package made.archive.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.service.document.WebImportFolderCache;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tâche planifiée pour nettoyer les sessions expirées de WebImportFolderCache
 * (fichiers d'un dossier distant déjà téléchargés en attente de confirmation).
 * S'exécute automatiquement toutes les minutes — même rythme que
 * OcrSessionCleanupScheduler.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class WebImportFolderCacheCleanupScheduler
{
    private final WebImportFolderCache webImportFolderCache;

    @Scheduled(fixedDelay = 60000)  // 1 minute
    public void cleanupExpiredSessions()
    {
        try
        {
            webImportFolderCache.cleanup();
        }
        catch (Exception e)
        {
            log.error("[WebImportFolderCacheCleanup] Erreur lors du nettoyage : {}", e.getMessage());
        }
    }
}
