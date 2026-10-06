package made.archive.service.integrite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.NotificationType;
import made.archive.entite.Role_Name;
import made.archive.entite.User;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.notification.NotificationService;
import made.archive.service.organisation.UniteOrganisationnelleService;

/**
 * Alertes de sécurité sur les preuves d'intégrité : réservées à l'administration (ADMIN globaux et ADMIN_UO ayant
 * autorité) — contrairement à une corruption de fichier, qui prévient aussi les éditeurs et utilisateurs ayant
 * accès. Une preuve réécrite en base est un signe d'intrusion, pas un incident de stockage : inutile (voire
 * contre-productif) d'en informer tous les utilisateurs d'un document. Best-effort, jamais bloquant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlerteIntegriteService
{
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final UniteOrganisationnelleService uniteOrganisationnelleService;
    private final AuditLogService auditLogService;

    /** Preuves d'un document altérées (le fichier, lui, est conforme à une preuve indépendante). */
    public void documentPreuveAlteree(Document document, String raison)
    {
        Long uoId = document.getUniteOrganisationnelle() != null ? document.getUniteOrganisationnelle().getId() : null;
        try
        {
            Map<UUID, User> destinataires = new LinkedHashMap<>();
            if (uoId != null)
            {
                uniteOrganisationnelleService.getAdminUOAvecAutoriteSur(uoId).forEach(u -> destinataires.put(u.getId(), u));
            }
            userRepository.findByRoleName(Role_Name.ADMIN).forEach(u -> destinataires.put(u.getId(), u));

            notificationService.notifier(new ArrayList<>(destinataires.values()), NotificationType.INTEGRITE_PREUVE_ALTEREE,
                "ALERTE SÉCURITÉ — les preuves d'intégrité du document \"" + document.getTitre()
                + "\" ont été modifiées en base de données en dehors de l'application (" + raison + "). "
                + "Le fichier archivé, lui, est resté conforme à sa signature d'origine. "
                + "Cela indique un accès non autorisé à la base : à investiguer.");
        }
        catch (Exception e)
        {
            log.warn("[Preuves] Notification (best-effort) échouée pour {} : {}", document.getId(), e.getMessage());
        }
        auditLogService.log(null, AuditAction.DOCUMENT_PREUVE_ALTEREE, AuditCible.DOCUMENT,
            document.getId().toString(), uoId,
            "Preuves d'intégrité altérées pour le document \"" + document.getTitre() + "\" — " + raison, false);
    }

    /** Anomalie globale (ancrage du catalogue, scellement du journal) — pas rattachée à un document. */
    public void anomalieGlobale(String description)
    {
        try
        {
            notificationService.notifier(userRepository.findByRoleName(Role_Name.ADMIN),
                NotificationType.INTEGRITE_PREUVE_ALTEREE,
                "ALERTE SÉCURITÉ — " + description);
        }
        catch (Exception e)
        {
            log.warn("[Preuves] Notification globale (best-effort) échouée : {}", e.getMessage());
        }
        auditLogService.log(null, AuditAction.CATALOGUE_ANCRAGE_ROMPU, description, false);
    }
}
