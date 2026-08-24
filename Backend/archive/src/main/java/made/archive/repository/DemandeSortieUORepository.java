package made.archive.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import made.archive.entite.DemandeSortieUO;
import made.archive.entite.StatutDemande;

@Repository
public interface DemandeSortieUORepository extends JpaRepository<DemandeSortieUO, Long> 
{
    List<DemandeSortieUO> findByUniteIdAndStatut(Long uniteId, StatutDemande statut);

    List<DemandeSortieUO> findByDemandeurId(Long userId);
}