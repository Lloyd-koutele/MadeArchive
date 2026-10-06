package made.archive.entite;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "documents", uniqueConstraints = @UniqueConstraint(
    name = "uk_document_original_sha256_uo",
    columnNames = { "original_sha256", "uo_id" }
))
@Entity
public class Document
{
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // length = 255 (pas 50) : le titre par défaut est le nom du fichier uploadé
    // (voir UploadSimple.tsx côté client), et un nom de fichier réel — mémoire,
    // thèse, rapport... — dépasse très facilement 50 caractères.
    @NotBlank(message = "Le titre est obligatoire")
    @Column(nullable = false, length = 255)
    private String titre;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeAccess access;

    /**
     * SHA-256 du fichier ORIGINAL (avant conversion LibreOffice + PDFBox).
     * Déterministe : le même fichier source produit toujours le même hash.
     * Usage : détection de doublons, scopée par UO (voir uniteOrganisationnelle) —
     * deux UO différentes peuvent archiver le même fichier source sans conflit,
     * mais une même UO ne peut l'archiver qu'une seule fois (quel que soit le
     * type de document choisi).
     */
    @NotBlank(message = "Le hash du fichier original est obligatoire")
    @Column(name = "original_sha256", nullable = false, length = 64)
    private String originalSha256;

    /**
     * SHA-256 du PDF/A-3b archivé en MinIO.
     * Calculé après conversion PDFBox — stable une fois archivé.
     * Usage : vérification d'intégrité lors des contrôles de routine
     * (recalculer depuis MinIO et comparer à cette valeur).
     * La signature PKI (pkiSignature) est calculée sur ce hash.
     */
    @NotBlank(message = "Le hash du PDF/A est obligatoire")
    @Column(name = "pdfa_sha256", nullable = false, length = 64)
    private String pdfaSha256;

    /**
     * SHA-256 du texte OCR extrait, normalisé (casse/accents/espaces retirés
     * — voir NormalisationNoms). Repère un contenu déjà archivé même sous une
     * autre FORME (ex. un .docx uploadé une première fois, puis sa conversion
     * PDF une autre fois — originalSha256 ne les verrait JAMAIS comme
     * identiques, ce sont des octets totalement différents, voir sa Javadoc).
     *
     * Nullable (contrairement à originalSha256/pdfaSha256, tous deux
     * obligatoires) : absent si l'OCR n'a produit aucun texte exploitable
     * (scan illisible, type de contenu non pris en charge).
     *
     * VOLONTAIREMENT PAS de contrainte unique en base, ni de blocage à
     * l'upload — juste un AVERTISSEMENT (voir DocumentOcrService, méthode de
     * détection d'un document similaire) : contrairement à originalSha256
     * (identité byte-à-byte, jamais ambiguë), une correspondance ici reste
     * probabiliste — l'OCR d'un même contenu peut varier légèrement selon la
     * source (scan vs. PDF à couche texte). Un blocage dur risquerait un faux
     * positif empêchant un archivage légitime ; l'utilisateur reste seul
     * juge, une fois informé.
     */
    @Column(name = "texte_normalise_sha256", length = 64)
    private String texteNormaliseSha256;

    @Column(length = 400)
    private String blockChainTxId;

    private LocalDate retentionUntil;

    @NotNull
    private LocalDateTime createAt = LocalDateTime.now();

    @NotNull
    private Long version;

    @NotBlank(message = "La cle de stockage PDF/A-3b est obligatoire")
    @Column(nullable = false, length = 300, unique = true)
    private String storageKey;

    @OneToMany(mappedBy = "document", fetch = FetchType.LAZY)
    private List<DataType> data;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IntegrityLevel integrityLevel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "groupe_id")
    private GroupeAccess groupe;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_document_id", nullable = false)
    private TypeDocument typeDocument;

   
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uo_id", nullable = false)
    @JsonIgnore
    private UniteOrganisationnelle uniteOrganisationnelle;

    @OneToOne(mappedBy = "document", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private FixityCheckResult fixityCheckResult;

    @Column(length = 600)
    private String pkiSignature;

    /**
     * Signature, par une clé du HSM, de l'ENREGISTREMENT canonique de ce document (identifiant, SHA-256 PDF/A et
     * original, UO, version, date de création, clé de stockage — voir service.integrite.PreuveIntegriteService).
     * Complète pkiSignature, qui ne couvre que pdfaSha256. Écrite une seule fois (déclencheur en base).
     */
    @Column(name = "signature_enregistrement", columnDefinition = "TEXT")
    private String signatureEnregistrement;

    /** Alias HSM de la clé qui a signé l'enregistrement (clé de l'éditeur, ou clé système pour les documents
     *  produits par le système et les scellements de rattrapage). */
    @Column(name = "signature_enregistrement_alias", length = 100)
    private String signatureEnregistrementAlias;

    /** Lot d'ancrage (AncrageCatalogue) dans lequel ce document est entré — null tant qu'il n'est pas ancré. */
    @Column(name = "ancrage_id")
    private Long ancrageId;

    /**
     * Jeton d'horodatage RFC 3161 (TimeStampToken, encodage DER brut) obtenu
     * auprès d'une autorité d'horodatage (TSA) sur pdfaSha256 — voir
     * HorodatageService. Preuve tierce indépendante de la BD elle-même que
     * ce hash existait à horodatageDate ; complète pkiSignature (qui prouve
     * QUI a signé/QUE le contenu n'a pas changé) sans s'y substituer. Null
     * si l'horodatage a échoué à l'upload — best-effort, voir
     * HorodatageRetryScheduler pour la reprise différée, jamais bloquant
     * pour l'archivage lui-même.
     *
     * @JdbcTypeCode(VARBINARY), PAS @Lob : sur PostgreSQL, un byte[] annoté
     * @Lob est mappé en "oid" (référence vers un Large Object externe dans
     * pg_largeobject), pas en "bytea" inline — constaté en conditions
     * réelles (colonne illisible telle quelle en SQL, exige une transaction
     * explicite pour être lue, voir DocumentExportRow, et surtout jamais
     * libéré automatiquement par Postgres à la suppression d'un document,
     * donc fuite silencieuse dans pg_largeobject). Un jeton RFC 3161 fait
     * quelques Ko : bytea (inline, sans les contraintes des Large Objects)
     * est le bon choix. Voir schema.sql pour la migration des jetons déjà
     * stockés en Large Object et le nettoyage des OID devenus orphelins.
     */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    private byte[] horodatageToken;

    /** Heure certifiée par le TSA, extraite du jeton — évite de le reparser pour un simple affichage. */
    private java.time.Instant horodatageDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id")
    private Dossier dossier;

    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_precedent_id")
    @JsonIgnore
    private Document documentPrecedent;

    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_racine_id")
    @JsonIgnore
    private Document documentRacine;

    @Column(nullable = false)
    private boolean derniereVersion = true;

    /**
     * Échéance de purge définitive — posée quand le document entre en
     * CORBEILLE (voir DocumentService.envoyerCorbeille), quel que soit son
     * statut d'origine (y compris CORRUPTED, qui utilisait autrefois ce
     * champ directement sans passer par CORBEILLE). Effacée à la
     * restauration.
     */
    private LocalDate suppressionPrevueLe;

    /**
     * Statut réel du document juste avant son passage en CORBEILLE — permet
     * à la restauration (DocumentService.restaurerDepuisCorbeille) de le
     * rendre exactement tel qu'il était (un document CORROMPU envoyé à la
     * corbeille et restauré redevient CORROMPU, pas ACTIVE : voir
     * DocumentDetailDto, le badge de corruption doit rester visible).
     * Null en dehors de CORBEILLE.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DocumentStatus statutAvantCorbeille;

    /**
     * Pourquoi ce document est en corbeille (ou l'a été avant sa purge) — voir MotifSuppression.
     * Null = suppression volontaire antérieure à l'introduction des motifs ("non renseigné").
     * Conservé dans la pierre tombale. FIN_DE_VIE = mis en corbeille par le système à l'échéance de
     * conservation : seul ce cas dépend du sort final du type pour la purge automatique (voir
     * DocumentRetentionService.estSupprimableAutomatiquement).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "motif_suppression", length = 30)
    private MotifSuppression motifSuppression;

    @Column(name = "commentaire_suppression", length = 500)
    private String commentaireSuppression;

    /** Dernier jour où l'alerte "suppression imminente" a été envoyée pour ce document (une par jour). */
    @Column(name = "alerte_suppression_le")
    private LocalDate alerteSuppressionLe;

    /**
     * L'éditeur a BLOQUÉ la suppression automatique de ce document en corbeille (motif, auteur, date
     * ci-dessous). Tant que c'est vrai, ni la tâche planifiée ni aucune autre voie ne le purge ; le
     * débloquer redonne un délai de grâce complet (voir DocumentRetentionService.debloquerElimination).
     */
    @Column(name = "elimination_bloquee", nullable = false)
    private boolean eliminationBloquee;

    @Column(name = "blocage_motif", length = 500)
    private String blocageMotif;

    @Column(name = "blocage_par")
    private UUID blocagePar;

    @Column(name = "blocage_le")
    private java.time.Instant blocageLe;

    /**
     * Activité (plan de classement de l'UO) propre à CE document — exception à l'activité par défaut de son
     * type. Null = il suit celle de son type (cas général). Jamais égale à celle du type : voir
     * PlanClassementService.activiteEffective et DocumentUploadeService/DocumentService.reclasser, qui
     * remettent null dans ce cas pour que le document continue de suivre son type.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_classement_noeud_id")
    @JsonIgnore
    private PlanClassementNoeud planClassementNoeud;

    /** Date de la purge définitive (pierre tombale). Posée par DocumentRetentionService.purgeOne. */
    @Column(name = "elimine_le")
    private java.time.Instant elimineLe;

    /** Qui a supprimé définitivement ce document — null = le système (suppression automatique). */
    @Column(name = "elimine_par")
    private UUID eliminePar;

    /** Procès-verbal d'élimination (autre Document, archivé dans MadeArchive) qui mentionne cette
     *  élimination — null tant qu'il n'est pas généré (réessayé chaque nuit). */
    @Column(name = "proces_verbal_id")
    private UUID procesVerbalId;

    // Emplacement physique de l'original papier, si ce document en a un — voir
    // PhysicalLocation. Nullable : un document purement numérique n'a pas
    // d'original physique à localiser. Doit toujours pointer vers un nœud
    // storagePoint=true, ACTIVE, de la MÊME UO que ce document — voir
    // PhysicalLocationService.resolvePourRattachement.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "physical_location_id")
    @JsonIgnore
    private PhysicalLocation physicalLocation;
}