package made.archive.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class MeilisearchDocumentDto
{
    // Champ id obligatoire pour Meilisearch
    private String id;
    private String titre;
    private String typeDocument;
    private String extractedText;
    private String status;
    private String access;
    private LocalDate retentionUntil;
    private String uploadedBy;
    private Long typeDocumentId;
    private String groupeId;

    /**
     * Valeurs des attributs (métadonnées) saisies par l'utilisateur pour ce
     * document — ex : ["Marvin", "2024-01-15"]. Indexées par Meilisearch comme
     * champ recherchable (par défaut, tous les champs non-filtrables le sont),
     * pour qu'une requête comme "Bulletin Marvin" trouve les documents de type
     * Bulletin dont une métadonnée contient "Marvin".
     */
    private List<String> metaDataValues;

    /** "Version 1", "Version 2"... ou "Final". null si jamais versionné. */
    private String versionLabel;
}