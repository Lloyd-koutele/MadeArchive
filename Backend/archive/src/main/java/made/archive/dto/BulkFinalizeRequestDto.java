package made.archive.dto;

import lombok.Data;

import java.util.List;

/**
 * Requête Phase 2 du bulk upload same-type.
 *
 * Le client envoie une liste de FinalizeUploadRequestDto,
 * un par fichier préalablement soumis en Phase 1.
 * Chaque entrée contient le sessionId retourné à la Phase 1
 * et les métadonnées validées/corrigées par l'utilisateur.
 *
 * Exemple JSON :
 * {
 *   "requests": [
 *     {
 *       "sessionId": "uuid-aaa",
 *       "documentUploadDto": {
 *         "titre": "Contrat_2024.pdf",
 *         "access": "PRIVE",
 *         "groupeNom": "Service RH",
 *         "typeDocumentId": 5,
 *         "uploadedById": "uuid-user",
 *         "integrityLevel": "STANDARD"
 *       },
 *       "metaDataValidated": [
 *         { "nom": "Date", "valeur": "2024-01-15", "typeValeur": "DATE" },
 *         { "nom": "Montant", "valeur": "150000", "typeValeur": "INTEGER" }
 *       ]
 *     },
 *     {
 *       "sessionId": "uuid-bbb",
 *       ...
 *     }
 *   ]
 * }
 */
@Data
public class BulkFinalizeRequestDto
{
    private List<FinalizeUploadRequestDto> requests;
}