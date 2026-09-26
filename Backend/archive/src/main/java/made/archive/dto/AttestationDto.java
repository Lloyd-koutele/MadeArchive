package made.archive.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Retourné par la génération d'une nouvelle attestation — voir
 * AttestationService.genererNouvelle. Chaque appel crée un jeton distinct,
 * jamais de réutilisation (voir Javadoc de l'entité Attestation).
 */
@Data
@Builder
public class AttestationDto
{
    private String token;

    // Lien public complet (frontend) encodé dans le QR du PDF — pratique pour
    // que le client affiche/partage le même lien sans avoir à le reconstruire.
    private String url;
}
