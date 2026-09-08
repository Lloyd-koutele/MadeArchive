package made.archive.service.document;

import java.util.List;
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

    @Async
    public void executer(FixityCheckTriggerService.Portee portee, List<Long> typeDocumentIds, List<Long> uoIds,
                          String libellePortee, User demandeur)
    {
        try
        {
            List<Document> resultats = switch (portee)
            {
                case TOUT -> fixityCheckService.verifyAllDocuments();
                case TYPES ->
                {
                    List<UUID> ids = documentRepository.findIdsByTypeDocumentIdIn(typeDocumentIds);
                    yield fixityCheckService.verifyDocumentsByIds(ids);
                }
                case UO ->
                {
                    List<UUID> ids = documentRepository.findIdsByUniteOrganisationnelleIdIn(uoIds);
                    yield fixityCheckService.verifyDocumentsByIds(ids);
                }
            };

            long corrompus = resultats.stream()
                .filter(d -> d.getStatus() == DocumentStatus.CORRUPTED)
                .count();

            String message = "Contrôle d'intégrité terminé — périmètre : " + libellePortee + ". "
                + resultats.size() + " document(s) dans le périmètre, " + corrompus
                + " actuellement marqué(s) CORROMPU (nouveaux ou déjà connus — les documents déjà "
                + "corrompus ou supprimés ne sont pas revérifiés).";

            notificationService.notifier(List.of(demandeur), NotificationType.FIXITY_CHECK_TERMINE, message);
            log.info("[FixityTrigger] Terminé — {} — {} document(s), {} corrompu(s)",
                libellePortee, resultats.size(), corrompus);
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
