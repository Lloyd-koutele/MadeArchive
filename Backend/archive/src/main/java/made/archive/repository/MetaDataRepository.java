package made.archive.repository;

import made.archive.entite.MetaData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MetaDataRepository extends JpaRepository<MetaData, Long> 
{
    List<MetaData> findByTypeDocumentId(Long typeDocumentId);

    // Retourne uniquement les MetaData sans regex — pour le premier document
    @Query("SELECT m FROM MetaData m WHERE m.typeDocument.id = :typeDocumentId " +
           "AND m.extractionRegex IS NULL")
    List<MetaData> findByTypeDocumentIdAndExtractionRegexIsNull(
        @Param("typeDocumentId") Long typeDocumentId);
}