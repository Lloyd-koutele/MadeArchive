package made.archive.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Retourné par la génération/récupération d'une attestation — voir
 * AttestationService.genererOuRecuperer.
 */
@Data
@Builder
public class AttestationDto
{
    private String token;

    // Lien public complet (frontend) encodé dans le QR du PDF — pratique pour
    // que le client affiche/partage le même lien sans avoir à le reconstruire.
    private String url;

    // true si une attestation existait déjà pour ce document (jeton réutilisé,
    // rien de nouveau généré) — permet au client d'adapter son message.
    private boolean dejaExistante;
}
