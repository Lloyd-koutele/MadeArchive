package made.archive.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO pour obtenir les métadonnées + clé publique d'un document
 * Permet la vérification manuelle
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMetadataDto 
{
    private UUID documentId;
    private String titre;
    private String sha256Hash;
    private String publicKey;
    private String pkiKey;
    private LocalDateTime createdAt;
    private LocalDate retentionUntil;
}