package made.archive.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import made.archive.entite.TypeDocument;

public interface TypeDocumentRepository extends JpaRepository<TypeDocument, Long>
{
    Optional<TypeDocument> findByNom(String nom);

    @Query("SELECT t FROM TypeDocument t WHERE t.user.id = :userId")
    List<TypeDocument> findByTypeDocumentCreateByUserId(@Param("userId") UUID userId);

    boolean existsByDocumentsNotEmptyAndId(Long id);

    boolean existsByPlanClassementNoeudId(Long noeudId);

    List<TypeDocument> findByPlanClassementNoeudIdIn(java.util.Collection<Long> noeudIds);

    /** [noeudId, nombre de types rattachés] pour tous les nœuds d'une UO — un seul SELECT groupé. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT t.planClassementNoeud.id, COUNT(t) FROM TypeDocument t " +
        "WHERE t.planClassementNoeud IS NOT NULL AND t.uniteOrganisationnelle.id = :uoId " +
        "GROUP BY t.planClassementNoeud.id")
    List<Object[]> countParNoeudPourUo(@org.springframework.data.repository.query.Param("uoId") Long uoId);

    /** Types dont la regex n'a jamais abouti — voir RegexGenerationService.retenterEchecs
     *  (reprise différée, LLM injoignable ou pas encore configuré au moment du premier essai). */
    List<TypeDocument> findByRegexGeneratedFalse();
    
    @Query("SELECT DISTINCT t FROM TypeDocument t " +
           "JOIN FETCH t.retention " +
           "LEFT JOIN FETCH t.metaData " +
           "ORDER BY t.id")
    List<TypeDocument> findAllWithRetentionAndMetaData();

    @Query("SELECT DISTINCT td FROM TypeDocument td LEFT JOIN FETCH td.metaData WHERE td.id = :id")
    Optional<TypeDocument> findByIdWithMetaData(@Param("id") Long id);

    List<TypeDocument> findByUniteOrganisationnelleId(Long uniteOrganisationnelleId);

    @Query("SELECT DISTINCT t FROM TypeDocument t " +
           "LEFT JOIN FETCH t.retention " +
           "LEFT JOIN FETCH t.metaData " +
           "WHERE t.uniteOrganisationnelle.id = :uoId")
    List<TypeDocument> findByUniteOrganisationnelleIdWithRetentionAndMetaData(@Param("uoId") Long uoId);

    /**
     * Même requête que ci-dessus, mais sur un ENSEMBLE d'UO — sert au
     * filtre "Type de document" de "Documents accessibles" (voir
     * TypeDocumentService.getTypeDocumentsVisibles) : un éditeur/utilisateur
     * simple doit voir les types de sa propre UO, un ADMIN_UO ceux de tout
     * son sous-arbre — jamais un seul appel par UO.
     */
    @Query("SELECT DISTINCT t FROM TypeDocument t " +
           "LEFT JOIN FETCH t.retention " +
           "LEFT JOIN FETCH t.metaData " +
           "WHERE t.uniteOrganisationnelle.id IN :uoIds " +
           "ORDER BY t.nom")
    List<TypeDocument> findByUniteOrganisationnelleIdInWithRetentionAndMetaData(@Param("uoIds") Set<Long> uoIds);

}