package made.archive.entite;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "physical_locations")
public class PhysicalLocation
{
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private LocationStatus status = LocationStatus.ACTIVE;

    @Column(nullable = false)
    private boolean storagePoint;

    /** Nombre maximal de documents que ce nœud peut recevoir — uniquement
     *  significatif si storagePoint=true. null = aucune limite (comportement
     *  historique). Voir PhysicalLocationService.resolvePourRattachement pour
     *  l'application de cette limite à chaque rattachement de document. */
    private Integer capaciteMax;

    /** Contrainte d'acceptation — voir LocationModeContrainte. LIBRE par
     *  défaut. @ColumnDefault (PAS juste l'initialiseur Java ci-dessous,
     *  invisible du schéma) : indispensable pour que ddl-auto=update sache
     *  ajouter cette colonne NOT NULL sur une table qui a déjà des lignes —
     *  sans lui, Postgres refuse (colonne NOT NULL sans valeur pour les
     *  lignes existantes) ; avec lui, Hibernate génère bien un DEFAULT
     *  'LIBRE' dans le ALTER TABLE, qui backfill ces lignes automatiquement. */
    @Column(nullable = false, length = 20)
    @ColumnDefault("'LIBRE'")
    @Enumerated(EnumType.STRING)
    private LocationModeContrainte modeContrainte = LocationModeContrainte.LIBRE;

    /** Type de document accepté — renseigné uniquement si modeContrainte=TYPE_UNIQUE. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_document_accepte_id")
    @JsonIgnore
    private TypeDocument typeDocumentAccepte;

    /** Dossier accepté — renseigné uniquement si modeContrainte=DOSSIER.
     *  PLUSIEURS nœuds peuvent pointer vers le MÊME dossier (volontairement
     *  pas de contrainte d'unicité) : un dossier volumineux peut ainsi
     *  occuper plusieurs "boîtes" physiques — voir LocationModeContrainte.DOSSIER. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id")
    @JsonIgnore
    private Dossier dossier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @JsonIgnore
    private PhysicalLocation parent;

    @OneToMany(mappedBy = "parent")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<PhysicalLocation> children;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unite_organisationnelle_id", nullable = false)
    @JsonIgnore
    private UniteOrganisationnelle uniteOrganisationnelle;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    @JsonIgnore
    private User createdBy;

    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    @JsonIgnore
    private User updatedBy;
}
