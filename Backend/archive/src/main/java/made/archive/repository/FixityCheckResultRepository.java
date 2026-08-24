package made.archive.repository;

import made.archive.entite.FixityCheckResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FixityCheckResultRepository extends JpaRepository<FixityCheckResult, UUID> 
{
    Optional<FixityCheckResult> findByDocumentId(UUID documentId);
    
    List<FixityCheckResult> findByDocumentIdIn(List<UUID> documentIds);
    
    List<FixityCheckResult> findByCheckedAtAfter(LocalDate date);
    
    List<FixityCheckResult> findByDocument_TypeDocument_Id(Long typeDocumentId);
}
