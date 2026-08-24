package made.archive.dto;
 
import lombok.Builder;
import lombok.Data;
 
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
 
@Data
@Builder
public class DocumentDetailDto
{
    private UUID          documentId;
    private String        titre;
    private Long          typeDocumentId;
    private String        typeDocumentNom;
    private String        status;
    private String        access;
    private String        integrityLevel;
    private String        pdfaSha256;
    private String        originalSha256;
    private LocalDate     retentionUntil;
    private LocalDateTime createAt;
    private Long          version;

    /** "Version 1", "Version 2"... ou "Final". null si jamais versionné. */
    private String        versionLabel;

    /** Historique complet de la chaîne (v1 → ... → Final), y compris ce document. */
    private List<DocumentVersionDto> historiqueVersions;

    /** Ce qui a déclenché status == CORRUPTED (hash différent, échec déchiffrement...). Null sinon. */
    private String corruptionRaison;

    /** Date de suppression définitive programmée si l'éditeur l'a demandée. Null sinon. */
    private LocalDate suppressionPrevueLe;

    /** true si l'utilisateur consultant ce détail est l'éditeur ayant déposé le document
     *  (seul habilité à demander sa suppression — voir DocumentService.planifierSuppression). */
    private boolean peutEtreSupprime;

    private List<MetaDataValueDto> metaData;

    /** Emplacement physique de l'original papier, s'il y en a un — voir PhysicalLocation. Null sinon. */
    private UUID physicalLocationId;

    /** Chemin lisible complet, ex. "Bâtiment A › Salle 204 › Rayon R03 › Boîte B001". Null si pas d'emplacement. */
    private String physicalLocationPath;

    /** true si l'utilisateur consultant peut modifier l'emplacement physique
     *  (même règle que planifierSuppression : éditeur + accès normal au document). */
    private boolean peutModifierEmplacement;

    /** UO du document — nécessaire côté client pour lister les emplacements physiques disponibles. */
    private Long uniteOrganisationnelleId;

    @Data
    @Builder
    public static class MetaDataValueDto
    {
        private String typeValeur;
        private String valeur;
    }
}
