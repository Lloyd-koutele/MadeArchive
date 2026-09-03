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
    
    @Query("SELECT DISTINCT t FROM TypeDocument t " +
           "JOIN FETCH t.retention " +
           "LEFT JOIN FETCH t.metaData " +
           "ORDER BY t.id")
    List<TypeDocument> findAllWithRetentionAndMetaData();

    @Query("SELECT DISTINCT td FROM TypeDocument td LEFT JOIN FETCH td.metaData WHERE td.id = :id")
    Optional<TypeDocument> findByIdWithMetaData(@Param("id") Long id);

    List<TypeDocument> findByUniteOrganisationnelleId(Long uniteOrganisationnelleId);

    // IgnoreCase : "Contrat"/"CONTRAT" doivent être détectés comme le même nom dans la
    // même UO — aucune contrainte d'unicité n'existe côté base sur cette colonne, tout
    // repose sur cette vérification applicative (voir TypeDocumentService).
    Optional<TypeDocument> findByNomIgnoreCaseAndUniteOrganisationnelleId(String nom, Long uoId);

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