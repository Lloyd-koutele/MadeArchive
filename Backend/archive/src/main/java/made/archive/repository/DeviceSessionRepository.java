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

    /** Retrouve aussi un jeton déjà échangé ou révoqué (détection de réutilisation). Verrou : sérialise deux renouvellements simultanés. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DeviceSession> findByRefreshToken(String refreshToken);

    @Modifying
    @Query("UPDATE DeviceSession d SET d.revoked = true WHERE d.familleId = :famille")
    void revoquerFamille(@Param("famille") String famille);

    @Modifying
    @Query("DELETE FROM DeviceSession d WHERE d.user = :user AND d.rotatedAt IS NOT NULL AND d.rotatedAt < :avant")
    void supprimerEchangesAnciens(@Param("user") User user, @Param("avant") Instant avant);

    List<DeviceSession> findAllByUserAndRevokedFalse(User user);

    @Modifying
    @Query("DELETE FROM DeviceSession d WHERE d.user = :user AND (d.revoked = true OR d.expiresAt < :now)")
    void deleteExpiredOrRevokedByUser(@Param("user") User user, @Param("now") Instant now);
}
