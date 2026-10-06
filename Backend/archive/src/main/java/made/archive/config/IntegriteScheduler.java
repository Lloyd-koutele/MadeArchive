package made.archive.config;

import java.util.concurrent.CompletableFuture;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.ChaineAuditVerificationDto;
import made.archive.service.audit.AuditChainService;
import made.archive.service.integrite.AlerteIntegriteService;
import made.archive.service.integrite.CatalogueAncrageService;
import made.archive.service.integrite.ScellementRattrapageService;

/**
 * Maintenance des preuves d'intégrité :
 *   - au démarrage (en tâche de fond) : scellement des documents plus anciens que la signature d'enregistrement,
 *     puis premier ancrage — la protection ne attend pas la nuit suivante ;
 *   - chaque nuit à 04:00 (après le contrôle de routine de 03:00) : rattrapage, ancrage du catalogue, reprise des
 *     jetons manquants, VÉRIFICATION de tous les ancrages et de la chaîne du journal d'audit ;
 *   - chaque heure : reprise des jetons d'ancrage encore manquants.
 *
 * Une anomalie alerte les ADMIN globaux (une alerte par anomalie nouvelle, pas une par nuit tant qu'elle persiste).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntegriteScheduler
{
    private final ScellementRattrapageService rattrapage;
    private final CatalogueAncrageService ancrage;
    private final AuditChainService auditChainService;
    private final AlerteIntegriteService alertes;

    private volatile String dernieresAnomalies = "";

    @EventListener(ApplicationReadyEvent.class)
    public void auDemarrage()
    {
        CompletableFuture.runAsync(() ->
        {
            try
            {
                rattrapage.rattraper();
                ancrage.ancrer();
            }
            catch (Exception e)
            {
                log.error("[Preuves] Maintenance de démarrage échouée : {}", e.getMessage(), e);
            }
        });
    }

    @Scheduled(cron = "${integrite.maintenance.cron:0 0 4 * * *}")
    public void maintenanceQuotidienne()
    {
        try
        {
            rattrapage.rattraper();
            ancrage.ancrer();
            ancrage.completerHorodatages();

            StringBuilder anomalies = new StringBuilder();
            CatalogueAncrageService.Rapport rapport = ancrage.verifier();
            rapport.anomalies().forEach(a -> anomalies.append(a).append('\n'));

            ChaineAuditVerificationDto chaine = auditChainService.verifierChaine(null);
            if (!chaine.isChaineIntacte())
            {
                chaine.getRuptures().forEach(r -> anomalies.append("Journal d'audit, entrée ").append(r.getId())
                    .append(" : ").append(r.getDescription()).append('\n'));
            }

            String courant = anomalies.toString();
            if (courant.isEmpty())
            {
                dernieresAnomalies = "";
                log.info("[Preuves] Vérification quotidienne : {} ancrage(s), {} document(s) ancré(s), journal intact",
                    rapport.ancrages(), rapport.documentsAncres());
            }
            else if (!courant.equals(dernieresAnomalies))
            {
                dernieresAnomalies = courant;
                log.error("[Preuves] ANOMALIES DÉTECTÉES :\n{}", courant);
                alertes.anomalieGlobale("la vérification quotidienne des preuves a détecté des incohérences "
                    + "(modification probable de la base de données) :\n" + courant);
            }
        }
        catch (Exception e)
        {
            log.error("[Preuves] Maintenance quotidienne échouée : {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 30 * * * *")
    public void reprendreHorodatagesManquants()
    {
        try
        {
            ancrage.completerHorodatages();
        }
        catch (Exception e)
        {
            log.error("[Preuves] Reprise des jetons d'ancrage échouée : {}", e.getMessage(), e);
        }
    }
}
