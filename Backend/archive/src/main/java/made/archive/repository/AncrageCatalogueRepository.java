package made.archive.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import made.archive.entite.AncrageCatalogue;

public interface AncrageCatalogueRepository extends JpaRepository<AncrageCatalogue, Long>
{
    AncrageCatalogue findTopByOrderByIdDesc();

    List<AncrageCatalogue> findAllByOrderByIdAsc();

    List<AncrageCatalogue> findByHorodatageTokenIsNullOrderByIdAsc();
}
