package made.archive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import made.archive.entite.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;


/**
 * DTO pour le résultat de vérification PKI
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentVerificationDto
{
    private boolean isValid;           // true = authentique, false = falsifié
    private String status;             // "AUTHENTIQUE ✓" ou "FALSIFIÉ ✗"
    private String message;            // Message descriptif
    private DocumentMetadata document; // Métadonnées du document (optionnel)

    /**
     * Métadonnées du document pour la vérification
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DocumentMetadata
    {
        private UUID id;
        private String titre;
        private String publicKey;
        private String pkiKey;
        private LocalDateTime createdAt;
        private LocalDate retentionUntil;
        private String uploadedBy;
        private long fileSizeBytes;
        private String originalSha256;
        private String pdfaSha256;
        /**
         * Construire à partir d'une entité Document
         */
        public static DocumentMetadata fromEntity(Document document)
        {
            return DocumentMetadata.builder()
                .id(document.getId())
                .titre(document.getTitre())
                .originalSha256(document.getOriginalSha256())
                .pdfaSha256(document.getPdfaSha256())
                .createdAt(document.getCreateAt())
                .retentionUntil(document.getRetentionUntil())
                .uploadedBy(document.getUploadedBy() != null ? document.getUploadedBy().getNom() : "Unknown")
                .publicKey(document.getUploadedBy() != null
                    ? document.getUploadedBy().getPkiPublicKey() : null)
                .pkiKey(document.getPkiSignature())
                .build();
        }
    }
}

/**
 * Response pour batch verification (pour vérifier plusieurs documents)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
class DocumentVerificationBatchDto
{
    private int total;                      
    private int validCount;                
    private int invalidCount;               
    private int errorCount;                 
    private java.util.List<DocumentVerificationDto> results;

    public void addValid()
    {
        validCount++;
        total++;
    }

    public void addInvalid()
    {
        invalidCount++;
        total++;
    }

    public void addError()
    {
        errorCount++;
        total++;
    }
}