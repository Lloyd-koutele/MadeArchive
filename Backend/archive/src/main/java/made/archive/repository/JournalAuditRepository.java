package made.archive.repository;

import made.archive.entite.JournalAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * JpaSpecificationExecutor permet de composer dynamiquement les filtres du
 * journal d'audit (acteur, action, cible, UO, période, texte libre) sans devoir
 * écrire une requête @Query différente pour chaque combinaison — voir AuditLogService.
 */
public interface JournalAuditRepository
    extends JpaRepository<JournalAudit, Long>, JpaSpecificationExecutor<JournalAudit>
{
}
