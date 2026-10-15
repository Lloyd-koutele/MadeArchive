package made.archive.config;

import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.exception.CleChaineAuditException;
import made.archive.service.audit.AuditChainService;
import made.archive.service.integrite.AlerteIntegriteService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chaînage et scellement du journal d'audit — deux tâches, deux cadences (voir AuditChainService) :
 *
 *   - chaînage en continu (10 secondes par défaut) : purement local, quelques microsecondes par
 *     entrée — une entrée ne reste modifiable sans trace que quelques secondes ;
 *   - scellement RFC 3161 (2 minutes par défaut) : un seul appel TSA par passage, et seulement
 *     s'il y a eu de l'activité depuis le dernier scellement réussi. Si l'horodatage échoue (TSA
 *     injoignable), les nouvelles tentatives sont espacées de 5 minutes (audit.chaine.delai-reprise-scellement-ms)
 *     pour ne pas harceler un TSA public.
 *
 * fixedDelay (et non fixedRate) : un passage ne démarre qu'une fois le précédent terminé, jamais
 * deux chaînages ou deux scellements qui se chevauchent sur une même instance. Les cadences sont
 * réglables dans application.properties (audit.chaine.*).
 * @EnableScheduling est déjà activé globalement via OcrSessionCleanupScheduler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditChainScheduler
{
    private final AuditChainService auditChainService;
    private final AlerteIntegriteService alertes;

    /** Délai minimal entre deux tentatives de scellement après un échec d'horodatage. */
    @Value("${audit.chaine.delai-reprise-scellement-ms:300000}")
    private long delaiRepriseScellementMs;

    private volatile Instant dernierEchecScellement;

    @Scheduled(fixedDelayString = "${audit.chaine.intervalle-chainage-ms:10000}",
               initialDelayString = "${audit.chaine.intervalle-chainage-ms:10000}")
    public void chainer()
    {
        try
        {
            auditChainService.calculerChainage();
        }
        catch (CleChaineAuditException e)
        {
            // Passage toutes les 10 s : une seule ligne, sans trace d'appel, et une alerte limitée (voir AlerteIntegriteService).
            log.error("[Chaine-Audit] Chaînage suspendu — {}", e.getMessage());
            alertes.cleChaineAuditAnormale(e.getMessage());
        }
        catch (Exception e)
        {
            log.error("[Chaine-Audit] Erreur lors du chaînage : {}", e.getMessage(), e);
        }
    }

    @Scheduled(fixedDelayString = "${audit.chaine.intervalle-scellement-ms:900000}",
               initialDelayString = "${audit.chaine.delai-premier-scellement-ms:60000}")
    public void sceller()
    {
        Instant maintenant = Instant.now();
        if (dernierEchecScellement != null && maintenant.isBefore(dernierEchecScellement.plusMillis(delaiRepriseScellementMs)))
        {
            return; // échec récent : on laisse passer le délai de reprise avant de rappeler la TSA
        }
        try
        {
            auditChainService.scellerSiNecessaire();
            dernierEchecScellement = auditChainService.scellementEnEchec() ? maintenant : null;
        }
        catch (Exception e)
        {
            log.error("[Chaine-Audit] Erreur lors du scellement : {}", e.getMessage(), e);
        }
    }
}
