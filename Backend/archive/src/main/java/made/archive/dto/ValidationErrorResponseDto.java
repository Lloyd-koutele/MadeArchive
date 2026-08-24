package made.archive.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO pour les réponses d'erreur de validation
 * 
 * Utilisé dans les cas d'erreur Phase 2 (validation échouée)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidationErrorResponseDto
{
    private String error;
    private String message;
    private List<ValidationErrorDetail> details;
    private Long timestamp;

    /**
     * Détail d'une erreur de validation pour un champ
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValidationErrorDetail
    {
        private String champ;
        private String message;
        private String typeAttendu;
        private String valeurFournie;
    }
}