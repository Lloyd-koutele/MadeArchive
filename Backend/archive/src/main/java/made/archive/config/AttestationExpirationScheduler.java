package made.archive.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.service.document.AttestationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purge quotidienne des attestations d'archivage expirées (2 jours — voir
 * AttestationService) — @EnableScheduling déjà activé globalement via
 * OcrSessionCleanupScheduler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttestationExpirationScheduler
{
    private final AttestationService attestationService;

    /** Tous les jours à 2h05 — juste après DocumentRetentionCleanupScheduler (2h00). */
    @Scheduled(cron = "0 5 2 * * *")
    public void purgerAttestationsExpirees()
    {
        try
        {
            attestationService.purgerAttestationsExpirees();
        }
        catch (Exception e)
        {
            log.error("[Attestation] Erreur lors de la purge des attestations expirées : {}",
                e.getMessage(), e);
        }
    }
}
