package made.archive.repository;

import java.util.UUID;

import made.archive.entite.AuditAction;
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
    /**
     * Un compte s'est-il DÉJÀ connecté au moins une fois ? Détermine, dans
     * UserService.supprimerUtilisateur, si sa suppression peut être réelle
     * (jamais servi) ou seulement logique (a déjà servi, donc potentiellement
     * référencé ailleurs — documents, projets, exports...).
     */
    boolean existsByActeurIdAndAction(UUID acteurId, AuditAction action);
}
