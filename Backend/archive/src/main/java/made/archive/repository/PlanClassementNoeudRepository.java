package made.archive.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import made.archive.entite.PlanClassementNoeud;

public interface PlanClassementNoeudRepository extends JpaRepository<PlanClassementNoeud, Long>
{
    /** Tous les nœuds d'une UO — l'arbre est reconstruit en mémoire (taille modeste). */
    List<PlanClassementNoeud> findByUniteOrganisationnelleId(Long uoId);

    boolean existsByParentId(Long parentId);

    boolean existsByUniteOrganisationnelleIdAndCode(Long uoId, String code);

    /** [id, code, libellé, parentId] de tous les nœuds des UO données — projection plate pour l'export (hors session). */
    @Query("SELECT n.id, n.code, n.libelle, n.parent.id FROM PlanClassementNoeud n WHERE n.uniteOrganisationnelle.id IN :uoIds")
    List<Object[]> findNoeudsPourExport(@Param("uoIds") Collection<Long> uoIds);
}
