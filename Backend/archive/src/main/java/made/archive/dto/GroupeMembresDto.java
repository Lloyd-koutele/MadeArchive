package made.archive.dto;

import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import made.archive.entite.User;

/**
 * Réponse de GroupeAccessService.getMembres() — la liste des membres seule ne
 * suffit plus au client pour savoir s'il doit afficher les contrôles
 * d'ajout/retrait : depuis que seul l'éditeur ayant archivé le document peut
 * gérer son groupe (voir GroupeAccessService.verifierEstUploadeur), le client
 * a besoin de savoir explicitement qui est l'uploadeur et si LUI (le
 * demandeur courant) a le droit de gérer — plutôt que de deviner en
 * interprétant un 400 sur un autre appel.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupeMembresDto
{
    private List<User> membres;

    /** L'éditeur ayant archivé le document — toujours membre, jamais retirable. */
    private UUID uploadeurId;

    /** true si le demandeur EST cet uploadeur — seul cas où il peut ajouter/retirer des membres. */
    private boolean peutGerer;
}
