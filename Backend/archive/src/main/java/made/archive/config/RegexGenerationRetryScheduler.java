package made.archive.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.service.document.RegexGenerationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reprend la génération de regex pour les types de documents dont le LLM
 * configuré (Ollama local et/ou API externe, voir OllamaService) était
 * injoignable au moment du premier essai — voir RegexGenerationService
 * (best-effort, ne bloque jamais l'archivage).
 *
 * Toutes les 8h : assez espacé pour ne jamais chevaucher un cycle précédent
 * (potentiellement long, un appel LLM par type en attente — voir
 * RegexGenerationService.retenterEchecs), assez fréquent pour qu'une panne de
 * LLM/mauvaise configuration réparée par l'admin soit reprise automatiquement
 * dans la journée, sans action manuelle de sa part.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegexGenerationRetryScheduler
{
    private final RegexGenerationService regexGenerationService;

    @Scheduled(cron = "0 0 */8 * * *")
    public void retenterEchecs()
    {
        try
        {
            regexGenerationService.retenterEchecs();
        }
        catch (Exception e)
        {
            log.error("[Regex-Retry] Erreur lors de la reprise différée : {}", e.getMessage(), e);
        }
    }
}
