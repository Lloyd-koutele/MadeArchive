package made.archive.service.integrite;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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

    /** Une alerte de clé au plus toutes les 6 h : un contrôle qui échoue en boucle ne doit pas inonder les admins. */
    private static final Duration INTERVALLE_ALERTE_CLE = Duration.ofHours(6);
    private final AtomicReference<Instant> derniereAlerteCle = new AtomicReference<>(Instant.EPOCH);

    /**
     * La clé de chiffrement des archives est absente ou n'est plus la bonne : erreur de configuration (aucun
     * document n'est marqué corrompu). Réservée aux ADMIN globaux, avec limitation de fréquence.
     */
    public void cleChiffrementAnormale(String description)
    {
        Instant maintenant = Instant.now();
        Instant derniere = derniereAlerteCle.get();
        if (Duration.between(derniere, maintenant).compareTo(INTERVALLE_ALERTE_CLE) < 0
            || !derniereAlerteCle.compareAndSet(derniere, maintenant))
        {
            return;
        }
        try
        {
            notificationService.notifier(userRepository.findByRoleName(Role_Name.ADMIN),
                NotificationType.INTEGRITE_PREUVE_ALTEREE,
                "ALERTE CONFIGURATION — " + description
                + " Les documents ne sont pas marqués corrompus : archivage et contrôles d'intégrité suspendus "
                + "tant que la clé n'est pas rétablie.");
        }
        catch (Exception e)
        {
            log.warn("[CleChiffrement] Notification (best-effort) échouée : {}", e.getMessage());
        }
        auditLogService.log(null, AuditAction.CLE_CHIFFREMENT_ANOMALIE, description, false);
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
