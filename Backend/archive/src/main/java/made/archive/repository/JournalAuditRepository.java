package made.archive.repository;

import java.util.List;
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
     * référencé ailleurs — documents, dossiers, exports...).
     */
    boolean existsByActeurIdAndAction(UUID acteurId, AuditAction action);

    /** Entrées pas encore chaînées — voir AuditChainService.calculerChainage. */
    List<JournalAudit> findByChainHashIsNullOrderByIdAsc();

    /** Dernière entrée déjà chaînée (le "bout" actuel de la chaîne), ou null
     *  si la chaîne n'a encore jamais été amorcée. */
    JournalAudit findTopByChainHashIsNotNullOrderByIdDesc();

    /** Toute la plage chaînée, dans l'ordre — pour la vérification à la
     *  demande (voir AuditChainService.verifierChaine). */
    List<JournalAudit> findByChainHashIsNotNullOrderByIdAsc();
}
