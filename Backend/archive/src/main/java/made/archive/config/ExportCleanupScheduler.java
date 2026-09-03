package made.archive.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.ExportJob;
import made.archive.repository.ExportJobRepository;

/**
 * Purge quotidienne des exports administratifs expirés (voir
 * ExportJob.expireAt / DocumentExportProperties.retentionHours) — le ZIP
 * généré contient du contenu DÉCHIFFRÉ, potentiellement des documents
 * privés : il ne doit jamais rester sur le disque du serveur au-delà de sa
 * durée de vie annoncée, qu'il ait été téléchargé ou non.
 *
 * 4h du matin — décalée après la purge de rétention documentaire (2h) et le
 * contrôle de fixité (3h) pour ne pas cogner le disque/la base en même
 * temps. @EnableScheduling déjà activé globalement via
 * OcrSessionCleanupScheduler.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class ExportCleanupScheduler
{
    private final ExportJobRepository exportJobRepository;

    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void purgerExportsExpires()
    {
        try
        {
            List<ExportJob> expires = exportJobRepository.findByExpireAtLessThanEqual(LocalDateTime.now());
            if (expires.isEmpty())
            {
                return;
            }

            int fichiersSupprimes = 0;
            for (ExportJob job : expires)
            {
                if (job.getCheminZip() != null)
                {
                    try
                    {
                        if (Files.deleteIfExists(Path.of(job.getCheminZip())))
                        {
                            fichiersSupprimes++;
                        }
                    }
                    catch (IOException e)
                    {
                        log.warn("[ExportCleanup] Suppression du fichier {} échouée : {}",
                            job.getCheminZip(), e.getMessage());
                    }
                }
            }

            exportJobRepository.deleteAll(expires);
            log.info("[ExportCleanup] {} export(s) expiré(s) purgé(s) ({} fichier(s) ZIP supprimé(s))",
                expires.size(), fichiersSupprimes);
        }
        catch (Exception e)
        {
            log.error("[ExportCleanup] Erreur lors de la purge des exports expirés : {}", e.getMessage(), e);
        }
    }
}
