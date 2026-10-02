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

    /** Statut d'avant corbeille (ex. "CORRUPTED") — non-null uniquement quand
     *  status == "CORBEILLE" (voir Document.statutAvantCorbeille). Un document
     *  corrompu envoyé à la corbeille reste identifiable comme tel (badge). */
    private String statutAvantCorbeille;

    /** Date de suppression définitive programmée — non-null uniquement quand status == "CORBEILLE". */
    private LocalDate suppressionPrevueLe;

    /** Durée de rétention (en années) configurée sur le TYPE de ce document —
     *  null si ce type n'a pas de limite de rétention. Utilisée côté client
     *  uniquement pour informer l'éditeur, au moment de restaurer un document
     *  dont retentionUntil est déjà dépassé, de la durée qu'aurait la
     *  nouvelle échéance s'il choisit de la renouveler (voir
     *  DocumentService.restaurerDepuisCorbeille). */
    private Long retentionYearsType;

    /** true si l'utilisateur consultant peut envoyer ce document à la corbeille, ou
     *  le restaurer s'il y est déjà — voir DocumentService.envoyerCorbeille/
     *  restaurerDepuisCorbeille (éditeur ayant accès normal au document, pas
     *  seulement son uploadeur). */
    private boolean peutGererCorbeille;

    private List<MetaDataValueDto> metaData;

    /** Emplacement physique de l'original papier, s'il y en a un — voir PhysicalLocation. Null sinon. */
    private UUID physicalLocationId;

    /** Chemin lisible complet, ex. "Bâtiment A › Salle 204 › Rayon R03 › Boîte B001". Null si pas d'emplacement. */
    private String physicalLocationPath;

    /** true si l'utilisateur consultant peut modifier l'emplacement physique
     *  (même règle que envoyerCorbeille/modifierEmplacementPhysique : éditeur + accès normal au document). */
    private boolean peutModifierEmplacement;

    /** UO du document — nécessaire côté client pour lister les emplacements physiques disponibles. */
    private Long uniteOrganisationnelleId;

    /** Dossier auquel ce document est rattaché, s'il y en a un — voir Document.dossier. Null sinon. */
    private Long   dossierId;
    private String dossierNom;
    /** Fil d'Ariane complet jusqu'à ce dossier, ex. "DGE / M1" — racine en
     *  premier (voir DossierService.construireChemin). Null si pas de dossier. */
    private String dossierCheminComplet;

    /** true si l'utilisateur consultant peut rattacher/migrer/détacher ce document
     *  d'un dossier (même règle que peutModifierEmplacement : éditeur + accès normal au document). */
    private boolean peutModifierDossier;

    /** true si l'utilisateur consultant peut basculer PUBLIC ↔ PRIVÉ sur ce document
     *  (même règle que peutModifierEmplacement, ET jamais true si le document hérite
     *  de la confidentialité d'un dossier PRIVÉ — voir DocumentService.modifierAcces). */
    private boolean peutModifierAcces;

    /** Activité (plan de classement de l'UO) héritée du TYPE de ce document, ex.
     *  "03 Finances › 03.2 Factures". Null = type non classé. */
    private String activite;

    @Data
    @Builder
    public static class MetaDataValueDto
    {
        private String typeValeur;
        private String valeur;
    }
}
