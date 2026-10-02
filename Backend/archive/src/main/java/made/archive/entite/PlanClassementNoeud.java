package made.archive.entite;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Un nœud du plan de classement d'une UO — arborescence d'activités/fonctions
 * (ex. "03 Finances" > "03.2 Factures et paiements") INDÉPENDANTE de
 * l'organigramme : elle décrit à quoi servent les documents, pas qui les
 * produit. Un plan par UO (décision produit) : chaque nœud appartient à
 * exactement une UO, et un type de document ne se rattache qu'à un nœud de
 * sa propre UO. Les types de documents (pas les documents un par un) s'y
 * rattachent — voir TypeDocument.planClassementNoeud.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "plan_classement_noeuds",
       uniqueConstraints = @UniqueConstraint(name = "uk_plan_classement_code_uo", columnNames = { "uo_id", "code" }))
public class PlanClassementNoeud
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Code d'activité, unique dans l'UO (ex. "03.2"). */
    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 150)
    private String libelle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @JsonIgnore
    private PlanClassementNoeud parent;

    @OneToMany(mappedBy = "parent")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<PlanClassementNoeud> children;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uo_id", nullable = false)
    @JsonIgnore
    private UniteOrganisationnelle uniteOrganisationnelle;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
