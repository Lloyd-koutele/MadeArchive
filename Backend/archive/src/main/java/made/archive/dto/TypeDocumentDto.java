package made.archive.dto;

import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TypeDocumentDto 
{
    private Long id;
    private String nom;
    private List<MetaDataDto> metaData;
    private UUID userId;
    private Long retentionYears;
    private Long periodGrace;

    /**
     * CONSERVER / DETRUIRE / TRIER (voir entite.SortFinal) — en chaîne de
     * caractères (pas l'enum Java) pour ne pas forcer le frontend à connaître
     * le type exact, même convention que "status"/"access" ailleurs dans les
     * DTO de document. Modifiable même si le type a déjà des documents
     * rattachés (voir TypeDocumentService.modifierSortFinal) — contrairement
     * au reste de ce DTO, verrouillé par hasLinkedDocuments une fois utilisé.
     */
    private String sortFinal;

    /** Activité (plan de classement de l'UO) du type — null = "Non classé". Modifiable
     *  même si le type a des documents (voir PlanClassementService.rattacherType). */
    private Long planClassementNoeudId;
    /** "03 Finances › 03.2 Factures" — chemin lisible complet, null si non classé. */
    private String activite;

    private List <DocumentDetailDto> documents;
    private Long uoId;
    private String uoNom;

    // Reflètent TypeDocument.regexGenerated/extractionRegexJson — absents ici
    // jusqu'à présent, le frontend recevait donc toujours `undefined` et
    // affichait "Pas encore générées" même quand la base avait un vrai
    // regex généré (voir TypeDocumentMapper.toDto).
    private Boolean regexGenerated;
    private String extractionRegexJson;
}
