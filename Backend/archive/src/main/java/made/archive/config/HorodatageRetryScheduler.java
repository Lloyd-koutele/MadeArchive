package made.archive.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.service.document.HorodatageService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reprend l'horodatage RFC 3161 des documents pour qui il a échoué (TSA
 * injoignable...) ou n'a jamais été tenté à l'upload — voir HorodatageService
 * (best-effort, ne bloque jamais l'archivage).
 *
 * Toutes les heures : contrairement à la vérification d'intégrité (une fois
 * par jour suffit, coûteuse — retélécharge chaque PDF/A) ou la purge de
 * rétention (une échéance légale, pas urgente à la minute près), un document
 * sans horodatage reste temporairement sans cette preuve tierce — mieux vaut
 * réessayer plus souvent tant que ça reste raisonnable pour un TSA public.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HorodatageRetryScheduler
{
    private final HorodatageService horodatageService;

    @Scheduled(cron = "0 0 * * * *")
    public void retenterEchecs()
    {
        try
        {
            horodatageService.retenterEchecs();
        }
        catch (Exception e)
        {
            log.error("[Horodatage] Erreur lors de la reprise différée : {}", e.getMessage(), e);
        }
    }
}
