package made.archive.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import made.archive.entite.ExportJob;

public interface ExportJobRepository extends JpaRepository<ExportJob, UUID>
{
    /** Jobs expirés (voir ExportCleanupScheduler) — le ZIP et cette ligne sont purgés,
     *  qu'il ait été téléchargé ou non : il contient du contenu déchiffré. */
    List<ExportJob> findByExpireAtLessThanEqual(LocalDateTime maintenant);
}
