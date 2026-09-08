package made.archive.repository;

import made.archive.entite.FixityCheckResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface FixityCheckResultRepository extends JpaRepository<FixityCheckResult, UUID>
{
    Optional<FixityCheckResult> findByDocumentId(UUID documentId);

    List<FixityCheckResult> findByDocumentIdIn(List<UUID> documentIds);

    // Type aligné sur FixityCheckResult.checkedAt (Instant, pas LocalDate
    // depuis le dédoublonnage des déclenchements manuels — voir sa Javadoc) :
    // un dérivé de requête Spring Data doit correspondre exactement au type
    // du champ, sinon l'application échoue au DÉMARRAGE (métamodèle invalide),
    // pas seulement à l'appel.
    List<FixityCheckResult> findByCheckedAtAfter(Instant instant);

    List<FixityCheckResult> findByDocument_TypeDocument_Id(Long typeDocumentId);

    /**
     * Parmi les IDs candidats, lesquels ont déjà été vérifiés depuis
     * {@code depuis} — utilisé pour ne PAS revérifier un document déjà
     * contrôlé il y a moins de 6h, même s'il est redemandé via un périmètre
     * différent de celui qui l'a couvert la première fois (voir
     * FixityCheckAsyncExecutor). Volontairement pas utilisé par
     * FixityCheckScheduler/verifyAllDocuments — la tâche planifiée
     * quotidienne, elle, doit toujours tout revérifier sans exception.
     */
    @Query("SELECT r.document.id FROM FixityCheckResult r "
        + "WHERE r.document.id IN :ids AND r.checkedAt >= :depuis")
    Set<UUID> findDocumentIdsCheckedSince(@Param("ids") Collection<UUID> ids, @Param("depuis") Instant depuis);
}
