package made.archive.repository;

import made.archive.entite.AuditChainSeal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditChainSealRepository extends JpaRepository<AuditChainSeal, Long>
{
    AuditChainSeal findTopByOrderByIdDesc();
}
