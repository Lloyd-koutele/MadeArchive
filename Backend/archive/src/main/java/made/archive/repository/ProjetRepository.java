package made.archive.repository;

import made.archive.entite.Projet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjetRepository extends JpaRepository<Projet, Long>
{
    List<Projet> findByUniteOrganisationnelleId(Long uoId);

    // IgnoreCase : "Archivage 2024"/"archivage 2024" doivent être détectés comme le même
    // nom dans la même UO — même règle que pour les UO et les types de document (voir
    // UniteOrganisationnelleRepository / TypeDocumentRepository).
    boolean existsByNomIgnoreCaseAndUniteOrganisationnelleId(String nom, Long uoId);

    /** Même vérification, en excluant le projet qu'on est justement en train de renommer (ProjetService.modifierProjet). */
    boolean existsByNomIgnoreCaseAndUniteOrganisationnelleIdAndIdNot(String nom, Long uoId, Long excludeId);
}
