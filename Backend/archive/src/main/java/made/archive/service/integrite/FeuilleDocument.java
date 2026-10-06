package made.archive.service.integrite;

import java.util.UUID;

/**
 * Ce qu'un document apporte à l'arbre de Merkle : exactement ses preuves, rien de modifiable par l'application
 * (le titre, les métadonnées, le statut... changent légitimement et n'en font donc pas partie).
 */
public record FeuilleDocument(UUID id, String pdfaSha256, String originalSha256, String signatureEnregistrement)
{
}
