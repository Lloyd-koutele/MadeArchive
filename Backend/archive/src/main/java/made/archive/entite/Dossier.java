package made.archive.entite;

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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Un dossier est un CONTENEUR qui regroupe des documents (dossier/affaire) —
 * ex : un dossier client, une affaire, un chantier. Un Document peut être
 * rattaché à zéro ou un dossier (voir Document.dossier), au dépôt ou après coup.
 * Un dossier peut aussi contenir d'autres dossiers (voir parent ci-dessous,
 * revu le 09/2026 — auparavant strictement plat).
 *
 * Pas de statut de cycle de vie : un dossier existe pour recevoir des
 * documents ; sa seule fin possible est la suppression (voir
 * DossierService.supprimerDossier — uniquement s'il ne contient aucun document
 * NI aucun sous-dossier, même s'il a des types attendus déclarés sans document
 * fourni).
 *
 * typesDocumentsAttendus : modèle de dossier — les types de documents que ce
 * dossier est censé contenir (ex : CV, Diplôme, Casier judiciaire). Peut être
 * vide à la création et complété après coup. PUREMENT INFORMATIF : sert à
 * afficher une checklist ("2/4 fournis") côté client, mais un document d'un
 * type hors-liste peut quand même être rattaché — pas de validation stricte
 * côté serveur.
 */
@Entity
@Table(name = "dossiers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Dossier
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Le nom du dossier est obligatoire")
    @Column(nullable = false, length = 150)
    private String nom;

    /** UO propriétaire du dossier — détermine qui est notifié à la création. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uo_id", nullable = false)
    @JsonIgnore
    private UniteOrganisationnelle uniteOrganisationnelle;

    /**
     * Dossier parent — null si racine de l'UO. Toujours dans la MÊME UO que
     * le parent (vérifié à la création, voir DossierService.creerDossier).
     * INVARIANT de confidentialité : un enfant ne peut jamais être plus
     * ouvert que son parent — un parent PRIVÉ force tout enfant PRIVÉ (voir
     * DossierService pour le détail des règles de cascade PUBLIC↔PRIVÉ).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @JsonIgnore
    private Dossier parent;

    /**
     * PUBLIC (défaut) ou PRIVÉ — même mécanique que Document.access. Un dossier
     * PRIVÉ a un GroupeAccess (ci-dessous) ; tout document versé dedans hérite
     * automatiquement de cette confidentialité et du MÊME groupe (voir
     * DocumentUploadeService) — jamais un groupe recréé par document.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeAccess access = TypeAccess.PUBLIC;

    /**
     * Groupe d'accès du dossier — non nul seulement si access == PRIVE. Le
     * créateur (creePar) en est le propriétaire : seul lui peut ajouter/retirer
     * des membres (voir DossierService), et il ne peut jamais s'en retirer
     * lui-même — même garde que pour un document privé.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "groupe_id")
    private GroupeAccess groupe;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id", nullable = false)
    private User creePar;

    @NotNull
    @Column(nullable = false)
    private LocalDateTime createAt;

    // @JsonIgnore : évite le cycle Dossier → documents → Document → dossier → ...
    @OneToMany(mappedBy = "dossier", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Document> documents;

    /**
     * Modèle de dossier — types de documents attendus (informatif, voir
     * Javadoc de la classe). Pas de mappedBy inverse sur TypeDocument, donc
     * pas de risque de cycle de sérialisation ici.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "dossier_types_documents_attendus",
        joinColumns = @JoinColumn(name = "dossier_id"),
        inverseJoinColumns = @JoinColumn(name = "type_document_id")
    )
    private List<TypeDocument> typesDocumentsAttendus;

    /**
     * Calculé à la lecture (jamais stocké) : true si ce dossier OU l'un de ses sous-dossiers contient des documents —
     * il ne peut alors plus être renommé, déplacé ni supprimé. Voir DossierService.getDossiersDeUO.
     */
    @jakarta.persistence.Transient
    private boolean verrouille;
}
