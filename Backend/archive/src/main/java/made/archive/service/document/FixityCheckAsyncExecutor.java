package made.archive.service.document;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.NotificationType;
import made.archive.entite.User;
import made.archive.repository.DocumentRepository;
import made.archive.repository.FixityCheckResultRepository;
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
    /** Même fenêtre que le cooldown de périmètre (FixityCheckTriggerService)
     *  — un document déjà vérifié dans cette fenêtre n'est pas revérifié,
     *  même redemandé via un périmètre DIFFÉRENT de celui qui l'a couvert la
     *  première fois (ex. son type, puis son UO qui le contient). */
    private static final Duration FENETRE_DEDOUBLONNAGE = Duration.ofHours(6);

    private final FixityCheckService fixityCheckService;
    private final DocumentRepository documentRepository;
    private final FixityCheckResultRepository fixityCheckResultRepository;
    private final NotificationService notificationService;

    @Async
    public void executer(FixityCheckTriggerService.Portee portee, List<Long> typeDocumentIds, List<Long> uoIds,
                          String libellePortee, User demandeur)
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

            Instant depuis = Instant.now().minus(FENETRE_DEDOUBLONNAGE);
            Set<UUID> dejaVerifies = candidats.isEmpty()
                ? Set.of()
                : fixityCheckResultRepository.findDocumentIdsCheckedSince(candidats, depuis);

            List<UUID> aVerifier = candidats.stream()
                .filter(id -> !dejaVerifies.contains(id))
                .toList();

            List<Document> resultats = fixityCheckService.verifyDocumentsByIds(aVerifier);

            long corrompus = resultats.stream()
                .filter(d -> d.getStatus() == DocumentStatus.CORRUPTED)
                .count();

            String message = "Contrôle d'intégrité terminé — périmètre : " + libellePortee + ". "
                + candidats.size() + " document(s) dans le périmètre, " + dejaVerifies.size()
                + " déjà vérifié(s) il y a moins de 6h (ignoré(s)), " + resultats.size()
                + " réellement vérifié(s) cette fois, " + corrompus + " actuellement marqué(s) CORROMPU.";

            notificationService.notifier(List.of(demandeur), NotificationType.FIXITY_CHECK_TERMINE, message);
            log.info("[FixityTrigger] Terminé — {} — {} candidat(s), {} ignoré(s) (déjà vérifiés), "
                + "{} vérifié(s), {} corrompu(s)",
                libellePortee, candidats.size(), dejaVerifies.size(), resultats.size(), corrompus);
        }
        catch (Exception e)
        {
            log.error("[FixityTrigger] Échec du contrôle manuel (périmètre : {}) : {}",
                libellePortee, e.getMessage(), e);
            notificationService.notifier(List.of(demandeur), NotificationType.FIXITY_CHECK_TERMINE,
                "Le contrôle d'intégrité demandé (périmètre : " + libellePortee
                + ") a échoué : " + e.getMessage());
        }
    }
}
