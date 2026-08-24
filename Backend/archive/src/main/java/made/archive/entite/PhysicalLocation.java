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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Emplacement physique — nœud d'un arbre entièrement libre (PAS de LocationType
 * en enum : chaque UO construit sa propre arborescence, "Bâtiment/Salle/Rayon"
 * n'est qu'un exemple parmi d'autres, voir le ticket d'origine).
 *
 * Deux natures de nœud, fixées via {@link #storagePoint} :
 *   - storagePoint = true  → un point de stockage : peut recevoir des
 *     documents (Document.physicalLocation), ne peut JAMAIS avoir d'enfant.
 *   - storagePoint = false → un nœud chemin : peut avoir un nombre illimité
 *     d'enfants, ne peut JAMAIS recevoir directement de document.
 * Voir PhysicalLocationService pour l'application de ces règles (et pour la
 * condition de bascule d'un type à l'autre — seulement si le nœud est "vide").
 *
 * Arbre scopé par UO (pas un arbre global partagé) : uniteOrganisationnelle
 * est obligatoire sur CHAQUE nœud (pas seulement la racine), et un enfant doit
 * appartenir à la même UO que son parent — voir PhysicalLocationService.
 *
 * status : ACTIVE/INACTIVE — désactiver un nœud chemin cascade automatiquement
 * l'INACTIVE à toute sa sous-arborescence (jamais aux nœuds frères), voir
 * PhysicalLocationService.desactiver.
 */
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

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private LocationStatus status = LocationStatus.ACTIVE;

    // Fixé à la création, modifiable seulement si le nœud est "vide" — voir
    // Javadoc de classe et PhysicalLocationService.changerTypeStockage.
    @Column(nullable = false)
    private boolean storagePoint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @JsonIgnore
    private PhysicalLocation parent;

    // EqualsAndHashCode/ToString.Exclude : même raison que TypeDocument.metaData
    // — éviter une comparaison/toString récursive sur la collection d'enfants.
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
