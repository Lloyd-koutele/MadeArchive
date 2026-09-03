package made.archive.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.TypeAccess;

public interface DocumentRepository extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document>
{
    Optional<Document> findDocumntByTitre(String titre);

    List<Document> findByTypeDocument_Id(Long id);

    /**
     * Tous les IDs de documents "vivants et normalement recherchables" — la
     * seule vérité sur ce qui a le droit de rester indexé dans Meilisearch.
     * Exclut DELETED (tombstoné) ET CORBEILLE (mis de côté volontairement,
     * ne doit plus remonter en recherche tant qu'il n'est pas restauré).
     * Utilisé par DocumentRetentionService.resynchroniserMeilisearch() pour
     * retrouver et nettoyer les entrées fantômes.
     */
    @Query("SELECT d.id FROM Document d WHERE d.status NOT IN "
        + "(made.archive.entite.DocumentStatus.DELETED, made.archive.entite.DocumentStatus.CORBEILLE)")
    List<UUID> findAllIdsNonSupprimes();

    @Query("SELECT d FROM Document d WHERE d.typeDocument.id = :typeDocumentId")
    List<Document> findByTypeDocumentId(@Param("typeDocumentId") Long typeDocumentId);

    @Query("SELECT d FROM Document d WHERE d.access = :accessType")
    List<Document> findDocumentsByAccessType(@Param("accessType") TypeAccess accessType);

    /**
     * Détection de doublons — fichier source identique, scopée par UO.
     * Deux UO différentes peuvent archiver le même fichier source sans conflit ;
     * une même UO ne peut l'archiver qu'une seule fois, quel que soit le type
     * de document choisi (voir la contrainte unique original_sha256 + uo_id).
     */
    boolean existsByOriginalSha256AndUniteOrganisationnelle_Id(String originalSha256, Long uoId);

    /**
     * Vérification défensive — PDF/A identique.
     * Utilisé en Phase 2 uniquement.
     */
    boolean existsByPdfaSha256(String pdfaSha256);

    /**
     * Un document ACTIF référence-t-il encore cette clé de stockage MinIO ?
     * Utilisé comme garde-fou avant toute suppression physique — voir
     * DocumentRetentionService.supprimerFichierMinioSiOrphelin(). Exclut DELETED :
     * un document déjà "tombstoné" (voir DocumentRetentionService) a par définition
     * plus besoin de son fichier — c'est justement le passage à ce statut qui doit
     * déclencher la suppression, pas l'empêcher.
     */
    boolean existsByStorageKeyAndStatusNot(String storageKey, DocumentStatus status);

    /**
     * Compte les documents par type pour un utilisateur donné — exclut les
     * tombstonés (DELETED) ET ceux en CORBEILLE (mis de côté volontairement,
     * ne doivent plus compter dans les dossiers de "Mes documents" tant
     * qu'ils n'ont pas été restaurés).
     * Retourne [typeDocumentId, typeDocumentNom, count].
     */
    @Query("SELECT d.typeDocument.id, d.typeDocument.nom, COUNT(d) " +
           "FROM Document d " +
           "WHERE d.uploadedBy.id = :userId AND d.status NOT IN ('DELETED', 'CORBEILLE') " +
           "GROUP BY d.typeDocument.id, d.typeDocument.nom " +
           "ORDER BY COUNT(d) DESC")
    List<Object[]> countDocumentsByTypeForUser(@Param("userId") UUID userId);

    /**
     * Recherche les documents d'un utilisateur pour un type donné, paginés —
     * exclut DELETED et CORBEILLE (voir findAllIdsNonSupprimes).
     */
    Page<Document> findByUploadedByIdAndTypeDocumentIdAndStatusNotIn(
        UUID uploadedById, Long typeDocumentId, Collection<DocumentStatus> statutsExclus,
        Pageable pageable);

    /**
     * Recherche les documents d'un utilisateur par leurs IDs (pour recherche
     * Meilisearch) — exclut DELETED et CORBEILLE.
     */
    List<Document> findByIdInAndUploadedByIdAndStatusNotIn(
        List<UUID> ids, UUID uploadedById, Collection<DocumentStatus> statutsExclus);

    /**
     * Liste tous les documents d'un utilisateur, paginés — exclut DELETED et CORBEILLE.
     */
    Page<Document> findByUploadedByIdAndStatusNotIn(
        UUID uploadedById, Collection<DocumentStatus> statutsExclus,
        Pageable pageable);

    /**
     * Documents dont la durée de rétention est dépassée et pas encore purgés.
     * Seule voie de suppression AUTOMATIQUE (pas demandée par un éditeur) du
     * système — voir DocumentRetentionService. Volontairement pas d'exclusion
     * CORBEILLE ici : un document en corbeille dont la rétention arrive
     * quand même à échéance doit être purgé tout pareil, la corbeille ne
     * doit jamais prolonger une rétention légale.
     */
    List<Document> findByRetentionUntilLessThanEqualAndStatusNot(
        LocalDate date, DocumentStatus excludedStatus);

    /**
     * Documents en CORBEILLE dont le délai de grâce de 3 jours est atteint —
     * voir DocumentService.envoyerCorbeille et DocumentRetentionService.
     */
    List<Document> findByStatusAndSuppressionPrevueLeLessThanEqual(
        DocumentStatus status, LocalDate date);

    /**
     * Documents dont l'horodatage RFC 3161 a échoué (ou n'a jamais été
     * tenté) à l'upload — voir HorodatageService (best-effort) et
     * HorodatageRetryScheduler (reprise différée). DELETED/CORBEILLE
     * exclus : inutile de retenter l'horodatage d'un document qui n'est
     * plus normalement consultable.
     */
    List<Document> findByHorodatageTokenIsNullAndStatusNotIn(
        List<DocumentStatus> statutsExclus);

    /**
     * Compte les documents d'un projet par type — sert à calculer la checklist
     * "types de documents attendus" (voir Projet.typesDocumentsAttendus).
     * Retourne [typeDocumentId, count].
     */
    @Query("SELECT d.typeDocument.id, COUNT(d) " +
           "FROM Document d " +
           "WHERE d.projet.id = :projetId AND d.status != 'DELETED' " +
           "GROUP BY d.typeDocument.id")
    List<Object[]> countDocumentsByTypeForProjet(@Param("projetId") Long projetId);

    /**
     * Un projet est "vide" (donc supprimable) s'il n'a AUCUN document
     * rattaché — même s'il a des types de documents attendus déclarés sans
     * document fourni, ça compte comme vide.
     */
    boolean existsByProjetId(Long projetId);

    /**
     * Un emplacement physique est "vide" (donc supprimable, ou son type
     * modifiable — voir PhysicalLocationService) s'il n'a aucun document
     * VIVANT rattaché ; un document déjà tombstoné (DELETED) ne compte pas,
     * il n'occupe plus réellement l'emplacement.
     */
    boolean existsByPhysicalLocationIdAndStatusNot(UUID physicalLocationId, DocumentStatus status);

    /**
     * Nombre de documents VIVANTS (non tombstonés) d'un type — utilisé pour
     * savoir si un document dont on corrige les métadonnées est le SEUL
     * document de son type (auquel cas les regex d'extraction, générées à
     * partir de lui seul, sont invalidées automatiquement — voir
     * DocumentService.modifierMetaData).
     */
    long countByTypeDocument_IdAndStatusNot(Long typeDocumentId, DocumentStatus status);

    /**
     * Toute la chaîne de versions d'un document, racine incluse — la racine
     * (v1) ne pointe pas vers elle-même (documentRacine == null), donc elle
     * n'est retrouvée qu'en comparant aussi sur id. Triée par version
     * croissante (v1 → ... → Final).
     */
    @Query("SELECT d FROM Document d " +
           "WHERE d.documentRacine.id = :racineId OR d.id = :racineId " +
           "ORDER BY d.version ASC")
    List<Document> findChaineVersions(@Param("racineId") UUID racineId);

    /**
     * Documents d'une ou plusieurs UO, pour l'export administratif (voir
     * DocumentExportService) — délimite seulement le périmètre UO, exclut
     * toujours DELETED et CORBEILLE si demandé. Le filtrage par visibilité
     * (public / privé dont l'appelant est membre) est fait ENSUITE, en
     * mémoire, par DocumentExportService — pas ici, cette requête est la
     * même que fetch_documents_for_uo_ids dans l'ancien script d'export.
     */
    @Query("SELECT d FROM Document d " +
           "WHERE d.uniteOrganisationnelle.id IN :uoIds AND d.status NOT IN :statutsExclus " +
           "ORDER BY d.uniteOrganisationnelle.nom, d.titre")
    List<Document> findForExport(@Param("uoIds") Collection<Long> uoIds,
                                  @Param("statutsExclus") Collection<DocumentStatus> statutsExclus);
}