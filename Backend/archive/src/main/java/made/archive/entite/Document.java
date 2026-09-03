package made.archive.entite;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
import jakarta.persistence.Lob;
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
     * Jeton d'horodatage RFC 3161 (TimeStampToken, encodage DER brut) obtenu
     * auprès d'une autorité d'horodatage (TSA) sur pdfaSha256 — voir
     * HorodatageService. Preuve tierce indépendante de la BD elle-même que
     * ce hash existait à horodatageDate ; complète pkiSignature (qui prouve
     * QUI a signé/QUE le contenu n'a pas changé) sans s'y substituer. Null
     * si l'horodatage a échoué à l'upload — best-effort, voir
     * HorodatageRetryScheduler pour la reprise différée, jamais bloquant
     * pour l'archivage lui-même.
     */
    @Lob
    private byte[] horodatageToken;

    /** Heure certifiée par le TSA, extraite du jeton — évite de le reparser pour un simple affichage. */
    private java.time.Instant horodatageDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id")
    private Projet projet;

    
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