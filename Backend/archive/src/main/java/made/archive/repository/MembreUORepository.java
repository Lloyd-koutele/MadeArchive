package made.archive.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import made.archive.entite.MembreUniteOrganisationnelle;

@Repository
public interface MembreUORepository extends JpaRepository<MembreUniteOrganisationnelle, Long> 
{
    Optional<MembreUniteOrganisationnelle> findByUserIdAndActifTrue(UUID userId);

    boolean existsByUserIdAndActifTrue(UUID userId);

    List<MembreUniteOrganisationnelle> findByUniteOrganisationnelleId(Long uniteId);

    List<MembreUniteOrganisationnelle> findByUserId(UUID userId);

    Long countByUserIdAndActifTrue(UUID userId);

    boolean existsByUserIdAndUniteOrganisationnelleIdAndActifTrue(UUID userId, Long uniteId);

    long countByUniteOrganisationnelleIdAndActifTrue(Long uoId);

    // ---- Ajouts pour éviter les N+1 ----

    @Query("SELECT m FROM MembreUniteOrganisationnelle m " +
           "JOIN FETCH m.uniteOrganisationnelle " +
           "JOIN FETCH m.user " +
           "WHERE m.user.id IN :userIds AND m.actif = true")
    List<MembreUniteOrganisationnelle> findByUserIdInAndActifTrue(@Param("userIds") Collection<UUID> userIds);

    @Query("SELECT m FROM MembreUniteOrganisationnelle m " +
           "JOIN FETCH m.user " +
           "WHERE m.uniteOrganisationnelle.id IN :uoIds AND m.actif = true")
    List<MembreUniteOrganisationnelle> findByUniteOrganisationnelleIdInAndActifTrue(@Param("uoIds") Collection<Long> uoIds);
}