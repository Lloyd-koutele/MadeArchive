package made.archive.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Voir SetupController — le frontend interroge ceci avant d'afficher la
 *  page de connexion ou l'assistant de première configuration. */
@Data
@AllArgsConstructor
public class SetupStatusDto
{
    private boolean needsSetup;
}
