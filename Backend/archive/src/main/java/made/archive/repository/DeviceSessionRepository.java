package made.archive.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import made.archive.entite.User;
import jakarta.persistence.LockModeType;
import made.archive.entite.DeviceSession;

import java.time.Instant;
import java.util.List;


public interface DeviceSessionRepository extends JpaRepository<DeviceSession, Long> 
{
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DeviceSession> findByRefreshTokenAndRevokedFalse(String refreshToken);

    List<DeviceSession> findAllByUserAndRevokedFalse(User user);

    @Modifying
    @Query("DELETE FROM DeviceSession d WHERE d.user = :user AND (d.revoked = true OR d.expiresAt < :now)")
    void deleteExpiredOrRevokedByUser(@Param("user") User user, @Param("now") Instant now);
}
