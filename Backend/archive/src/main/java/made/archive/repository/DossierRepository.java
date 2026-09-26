package made.archive.repository;

import made.archive.entite.Dossier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DossierRepository extends JpaRepository<Dossier, Long>
{
    // Doublon détecté via NormalisationNoms (casse/accents/espaces), pas une simple
    // IgnoreCase SQL — voir DossierService.verifierNomUnique, qui compare en mémoire
    // après avoir chargé les dossiers concernés via les méthodes ci-dessous.
    List<Dossier> findByUniteOrganisationnelleId(Long uoId);

    /** Dossiers racine (sans parent) d'une UO — même logique que PhysicalLocation. */
    List<Dossier> findByParentIsNullAndUniteOrganisationnelleId(Long uoId);

    /** Enfants directs d'un dossier donné. */
    List<Dossier> findByParentId(Long parentId);

    /** Suppression (voir DossierService.supprimerDossier) : refusée si des sous-dossiers existent encore. */
    boolean existsByParentId(Long parentId);
}
