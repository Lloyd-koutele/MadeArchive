package made.archive.service.document;

import java.util.List;
import java.util.UUID;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.RedisCacheConfig;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.NotificationType;
import made.archive.entite.User;
import made.archive.repository.DocumentRepository;
import made.archive.service.notification.NotificationService;

/**
 * Bean SÉPARÉ de FixityCheckTriggerService — indispensable pour que @Async
 * fonctionne réellement : Spring AOP (proxy JDK/CGLIB) n'intercepte que les
 * appels venant de L'EXTÉRIEUR du bean, jamais un auto-appel "this.methode()"
 * depuis l'intérieur de la même classe. Si cette méthode vivait dans
 * FixityCheckTriggerService et y était appelée depuis declencher(), @Async
 * serait silencieusement ignoré — la vérification tournerait en synchrone,
 * bloquant la requête HTTP jusqu'à la fin (potentiellement très long pour
 * "tout le catalogue"), à l'exact opposé du but recherché.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FixityCheckAsyncExecutor
{
    private final FixityCheckService fixityCheckService;
    private final DocumentRepository documentRepository;
    private final NotificationService notificationService;
    private final CacheManager cacheManager;

    @Async
    public void executer(FixityCheckTriggerService.Portee portee, List<Long> typeDocumentIds, List<Long> uoIds,
                          String libellePortee, User demandeur, List<String> cles)
    {
        try
        {
            // Résolution UNIFIÉE des trois périmètres en simples listes d'IDs
            // — permet d'appliquer le même dédoublonnage aux trois, sans
            // dupliquer la logique. Volontairement PAS fixityCheckService
            // .verifyAllDocuments() ici pour "TOUT" (elle, réservée à
            // FixityCheckScheduler, ne doit jamais rien sauter).
            List<UUID> candidats = switch (portee)
            {
                case TOUT -> documentRepository.findAllIds();
                case TYPES -> documentRepository.findIdsByTypeDocumentIdIn(typeDocumentIds);
                case UO -> documentRepository.findIdsByUniteOrganisationnelleIdIn(uoIds);
            };

            // Plus de dédoublonnage : l'administrateur demande un contrôle précisément pour revérifier, tout de suite,
            // des documents qui ont pu déjà être contrôlés récemment (le périmètre d'une demande est plafonné).
            List<UUID> aVerifier = candidats;

            List<Document> resultats = fixityCheckService.verifyDocumentsByIds(aVerifier);

            long corrompus = resultats.stream()
                .filter(d -> d.getStatus() == DocumentStatus.CORRUPTED)
                .count();

            String message = "Contrôle d'intégrité terminé — périmètre : " + libellePortee + ". "
                + resultats.size() + " document(s) vérifié(s) sur " + candidats.size() + " dans le périmètre, "
                + corrompus + " actuellement marqué(s) CORROMPU.";

            notificationService.notifier(List.of(demandeur), NotificationType.FIXITY_CHECK_TERMINE, message);
            log.info("[FixityTrigger] Terminé — {} — {} candidat(s), {} vérifié(s), {} corrompu(s)",
                libellePortee, candidats.size(), resultats.size(), corrompus);
        }
        catch (Exception e)
        {
            log.error("[FixityTrigger] Échec du contrôle manuel (périmètre : {}) : {}",
                libellePortee, e.getMessage(), e);
            notificationService.notifier(List.of(demandeur), NotificationType.FIXITY_CHECK_TERMINE,
                "Le contrôle d'intégrité demandé (périmètre : " + libellePortee
                + ") a échoué : " + e.getMessage());
        }
        finally
        {
            // Libère le périmètre : une nouvelle demande est de nouveau possible.
            Cache cache = cacheManager.getCache(RedisCacheConfig.CACHE_FIXITY_COOLDOWN);
            if (cache != null && cles != null)
            {
                cles.forEach(cache::evict);
            }
        }
    }
}
