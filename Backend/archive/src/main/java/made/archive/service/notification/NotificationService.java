package made.archive.service.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.Notification;
import made.archive.entite.NotificationType;
import made.archive.entite.User;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService
{
    private final NotificationRepository notificationRepository;

    /**
     * Notifie un ensemble de destinataires — une ligne Notification par
     * destinataire. Un même utilisateur apparaissant dans plusieurs groupes
     * de destinataires (ex : éditeur qui est aussi ADMIN_UO) n'est notifié
     * qu'une seule fois. Best-effort par nature : appelée en dehors des
     * transactions critiques, ne doit jamais faire échouer l'action déclenchante.
     */
    @Transactional
    public void notifier(Collection<User> destinataires, NotificationType type, String message)
    {
        if (destinataires == null || destinataires.isEmpty())
        {
            return;
        }

        Map<UUID, User> uniques = new LinkedHashMap<>();
        for (User u : destinataires)
        {
            if (u != null && u.getId() != null)
            {
                uniques.putIfAbsent(u.getId(), u);
            }
        }

        if (uniques.isEmpty())
        {
            return;
        }

        LocalDateTime maintenant = LocalDateTime.now();
        List<Notification> notifications = uniques.values().stream()
            .map(u -> {
                Notification n = new Notification();
                n.setUser(u);
                n.setType(type);
                n.setMessage(message);
                n.setCreateAt(maintenant);
                n.setRead(false);
                return n;
            })
            .toList();

        notificationRepository.saveAll(notifications);
        log.info("[Notification] {} destinataire(s) notifié(s) — type {}", notifications.size(), type);
    }

    @Transactional(readOnly = true)
    public List<Notification> getMesNotifications(UUID userId)
    {
        return notificationRepository.findByUserIdOrderByCreateAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public long countNonLues(UUID userId)
    {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    @Transactional
    public void marquerCommeLue(Long notificationId, User currentUser)
    {
        Notification n = notificationRepository.findById(notificationId)
            .orElseThrow(() -> new BusinessException("Notification introuvable"));

        if (!n.getUser().getId().equals(currentUser.getId()))
        {
            throw new AccessDeniedException("Cette notification ne vous appartient pas");
        }

        if (!n.isRead())
        {
            n.setRead(true);
            notificationRepository.save(n);
        }
    }

    @Transactional
    public void marquerToutesCommeLues(User currentUser)
    {
        List<Notification> nonLues = notificationRepository
            .findByUserIdAndIsReadFalseOrderByCreateAtDesc(currentUser.getId());

        nonLues.forEach(n -> n.setRead(true));
        notificationRepository.saveAll(nonLues);
    }
}
