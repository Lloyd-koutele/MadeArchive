package made.archive.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import made.archive.entite.PhysicalLocation;

public interface PhysicalLocationRepository extends JpaRepository<PhysicalLocation, UUID>
{
    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    List<PhysicalLocation> findByParentId(UUID parentId);

    List<PhysicalLocation> findByParentIsNullAndUniteOrganisationnelleId(Long uniteOrganisationnelleId);

    /** Tous les nœuds d'une UO (tout statut confondu) — pour reconstruire l'arbre complet en mémoire. */
    List<PhysicalLocation> findByUniteOrganisationnelleId(Long uniteOrganisationnelleId);

    List<PhysicalLocation> findByUniteOrganisationnelleIdAndStoragePointTrueAndStatus(
        Long uniteOrganisationnelleId, made.archive.entite.LocationStatus status);

    Optional<PhysicalLocation> findByIdAndUniteOrganisationnelleId(UUID id, Long uniteOrganisationnelleId);
}
