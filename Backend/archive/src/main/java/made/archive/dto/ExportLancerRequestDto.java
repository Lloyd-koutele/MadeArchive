package made.archive.dto;

import java.util.List;
import java.util.UUID;

import lombok.Data;

/**
 * Requête de déclenchement d'un export — voir DocumentExportController.
 * docIds optionnel : si fourni, restreint le périmètre UO à ces documents
 * précis (comme --doc-ids dans le script), sinon tout le périmètre UO est pris.
 */
@Data
public class ExportLancerRequestDto
{
    private List<Long> uoIds;
    private List<UUID> docIds;
    private boolean separateProjects;
    private boolean excludeCorbeille;

    /** Réservé à ROLE_ADMIN — inclut les documents PRIVÉS dont le demandeur
     *  n'est pas membre. Exige motif non vide. */
    private boolean includePriveNonMembre;

    /** Obligatoire si includePriveNonMembre=true — affiché dans la
     *  notification obligatoire envoyée aux membres concernés. */
    private String motif;
}
