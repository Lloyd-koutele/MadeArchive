package made.archive.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import made.archive.entite.AuditChainConfig;

public interface AuditChainConfigRepository extends JpaRepository<AuditChainConfig, Long>
{
}
