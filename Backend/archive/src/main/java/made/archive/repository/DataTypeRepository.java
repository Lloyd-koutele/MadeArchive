package made.archive.repository;

import made.archive.entite.DataType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DataTypeRepository extends JpaRepository<DataType, Long>
{
    List<DataType> findByDocument_Id(UUID documentId);

    @Modifying
    @Query("DELETE FROM DataType d WHERE d.document.id = :documentId")
    void deleteByDocumentId(@Param("documentId") UUID documentId);

    /**
     * Valeurs d'attributs déjà confirmées (documents précédents) pour un type
     * donné — utilisé pour guider Tesseract (dictionnaire "user-words") sur
     * les futurs documents du même type. Vide pour le tout premier document.
     */
    @Query("SELECT DISTINCT d.valeur FROM DataType d " +
           "WHERE d.document.typeDocument.id = :typeDocumentId AND d.valeur IS NOT NULL")
    List<String> findDistinctValeursByTypeDocumentId(@Param("typeDocumentId") Long typeDocumentId);

    /**
     * Métadonnées extraites de plusieurs documents en une requête (export enrichi) :
     * [documentId (UUID), libellé (String, null pour un ancien DataType sans libellé), valeur].
     */
    @Query("SELECT d.document.id, m.nom, d.valeur FROM DataType d LEFT JOIN d.metaData m " +
           "WHERE d.document.id IN :ids")
    List<Object[]> findMetadonneesPourExport(@Param("ids") Collection<UUID> ids);
}
