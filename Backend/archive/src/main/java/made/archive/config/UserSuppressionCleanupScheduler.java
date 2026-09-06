package made.archive.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.service.user.UserService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tâche planifiée qui exécute réellement les suppressions d'utilisateurs dont
 * le délai de grâce de 2 jours est écoulé — voir UserService.demanderSuppression
 * / executerSuppressionsEnAttente et User.suppressionPrevueLe. Même principe et
 * même rythme que DocumentRetentionCleanupScheduler pour la corbeille de
 * documents (délai de grâce comparable) : une fois par jour suffit, l'échéance
 * est une date (granularité jour), pas un horodatage précis.
 *
 * @EnableScheduling est déjà activé globalement via OcrSessionCleanupScheduler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSuppressionCleanupScheduler
{
    private final UserService userService;

    /**
     * Tous les jours à 2h05 — juste après la purge documentaire (2h00, voir
     * DocumentRetentionCleanupScheduler), pour ne jamais les faire courir en
     * même temps sans raison.
     */
    @Scheduled(cron = "0 5 2 * * *")
    public void executerSuppressionsEnAttente()
    {
        try
        {
            userService.executerSuppressionsEnAttente();
        }
        catch (Exception e)
        {
            log.error("[Suppression utilisateur] Erreur lors de l'exécution des suppressions en attente : {}",
                e.getMessage(), e);
        }
    }
}
