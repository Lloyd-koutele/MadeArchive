package made.archive.repository;

import made.archive.entite.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, Long>
{
    List<Notification> findByUserIdOrderByCreateAtDesc(UUID userId);

    List<Notification> findByUserIdAndIsReadFalseOrderByCreateAtDesc(UUID userId);

    long countByUserIdAndIsReadFalse(UUID userId);
}
