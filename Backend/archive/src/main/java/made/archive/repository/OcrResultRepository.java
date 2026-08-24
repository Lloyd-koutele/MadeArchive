package made.archive.repository;

import made.archive.entite.OcrResult;
import made.archive.entite.OcrStatus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OcrResultRepository extends JpaRepository<OcrResult, UUID> 
{
    Optional<OcrResult> findByDocumentId(UUID documentId);

    // Vérifie si c'est le premier document OCRisé de ce type
    boolean existsByDocument_TypeDocument_Id(Long typeDocumentId);

    boolean existsByDocument_TypeDocument_IdAndStatus(Long typeDocumentId, OcrStatus status);

    long countByDocument_TypeDocument_IdAndStatus(Long typeDocumentId, OcrStatus status);
}