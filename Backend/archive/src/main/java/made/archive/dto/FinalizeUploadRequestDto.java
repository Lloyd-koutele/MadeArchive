package made.archive.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Requête du client pour la PHASE 2 (Finalize Upload)
 * 
 * Le client envoie :
 * - sessionId : UUID reçu dans la phase 1
 * - documentUploadDto : Informations du document (access, typeDocumentId, etc.)
 * - metaDataValidated : Métadonnées corrigées/validées par l'utilisateur
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinalizeUploadRequestDto
{
    private String sessionId;
    private DocumentUploadDto documentUploadDto;
    private List<MetaDataValueDto> metaDataValidated;

    /**
     * Représente une métadonnée avec sa valeur validée
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetaDataValueDto
    {
        private String nom;
        private String valeur;
        private String typeValeur;  // CHAR, STRING, INTEGER, DATE, etc.
    }
}