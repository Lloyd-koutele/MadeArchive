package made.archive.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Réponse de DocumentService.verifierFusionGroupe — appelée par le client
 * AVANT de rattacher un document privé à un projet privé, pour savoir s'il
 * faut avertir l'éditeur qu'une fusion de groupes aura lieu.
 *
 * groupesDifferents == false : rien à signaler — soit le projet ou le
 * document n'est pas privé, soit les deux groupes ont déjà exactement les
 * mêmes membres. Le rattachement peut se faire sans confirmation
 * supplémentaire (fusionnerGroupes=false suffit côté modifierProjetDocument).
 */
@Data
@Builder
public class FusionGroupeCheckDto
{
    private boolean groupesDifferents;

    /** Noms complets des membres du groupe du document qui seront ajoutés à celui du projet. */
    private List<String> membresQuiSerontAjoutes;
}
