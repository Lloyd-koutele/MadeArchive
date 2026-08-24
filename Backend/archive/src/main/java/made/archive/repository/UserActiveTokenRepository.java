package made.archive.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import made.archive.entite.User;
import jakarta.persistence.LockModeType;
import made.archive.entite.UserActiveToken;

public interface UserActiveTokenRepository extends JpaRepository<UserActiveToken, Long>
{
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserActiveToken> findByUser(User user);
}