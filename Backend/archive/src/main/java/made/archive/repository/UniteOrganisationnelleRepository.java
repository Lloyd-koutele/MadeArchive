package made.archive.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import made.archive.dto.UOCheminProjection;
import made.archive.entite.UniteOrganisationnelle;

@Repository
public interface UniteOrganisationnelleRepository extends JpaRepository<UniteOrganisationnelle, Long> 
{
    Optional<UniteOrganisationnelle> findByNom(String nom);

    boolean existsByNom(String nom);

    List<UniteOrganisationnelle> findByParentIsNull();

    @Query("SELECT u.id, u.parent.id FROM UniteOrganisationnelle u")
    List<UOParentProjection> findAllIdsEtParents();

    List<UniteOrganisationnelle> findByParentId(Long parentId);

    @Query("SELECT u FROM UniteOrganisationnelle u WHERE u.parent IS NULL")
    List<UniteOrganisationnelle> findRacines();

    @Query("SELECT COUNT(u) > 0 FROM UniteOrganisationnelle u WHERE u.parent.id = :id")
    boolean hasChildren(@Param("id") Long id);

    // IgnoreCase : "Esp"/"eSp"/"ESP" doivent être détectées comme le même nom au même
    // niveau — aucune contrainte d'unicité n'existe côté base sur cette colonne, tout
    // repose sur ces vérifications applicatives (voir UniteOrganisationnelleService).
    boolean existsByNomIgnoreCaseAndParentId(String nom, Long parentId);

    boolean existsByNomIgnoreCaseAndParentIsNull(String nom);

    // Calcule le chemin complet de TOUTES les UO en une seule requête (pour les listes)
    @Query(value = """
        WITH RECURSIVE hierarchie AS (
            SELECT id, nom, parent_id, nom::text AS chemin
            FROM unites_organisationnelles
            WHERE parent_id IS NULL
            UNION ALL
            SELECT u.id, u.nom, u.parent_id, CONCAT(h.chemin, '/', u.nom)
            FROM unites_organisationnelles u
            INNER JOIN hierarchie h ON u.parent_id = h.id
        )
        SELECT id AS id, chemin AS chemin FROM hierarchie
        """, nativeQuery = true)
    List<UOCheminProjection> findAllCheminsComplets();

    // Calcule le chemin complet d'UNE seule UO (pour create/update/getById)
    @Query(value = """
        WITH RECURSIVE remontee AS (
            SELECT id, nom, parent_id, nom::text AS chemin_partiel
            FROM unites_organisationnelles
            WHERE id = :id
            UNION ALL
            SELECT u.id, u.nom, u.parent_id, CONCAT(u.nom, '/', r.chemin_partiel)
            FROM unites_organisationnelles u
            INNER JOIN remontee r ON u.id = r.parent_id
        )
        SELECT chemin_partiel FROM remontee WHERE parent_id IS NULL
        """, nativeQuery = true)
    Optional<String> findCheminCompletById(@Param("id") Long id);

    interface UOParentProjection
    {
        Long getId();
        Long getParentId();
    }

}