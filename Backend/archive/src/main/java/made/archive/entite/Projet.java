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
 * Un projet est un CONTENEUR qui regroupe des documents (dossier/affaire) —
 * ex : un dossier client, une affaire, un chantier. Un Document peut être
 * rattaché à zéro ou un projet (voir Document.projet), au dépôt ou après coup.
 *
 * Pas de statut de cycle de vie : un projet existe pour recevoir des
 * documents ; sa seule fin possible est la suppression (voir
 * ProjetService.supprimerProjet — uniquement s'il ne contient aucun document,
 * même s'il a des types attendus déclarés sans document fourni).
 *
 * typesDocumentsAttendus : modèle de dossier — les types de documents que ce
 * projet est censé contenir (ex : CV, Diplôme, Casier judiciaire). Peut être
 * vide à la création et complété après coup. PUREMENT INFORMATIF : sert à
 * afficher une checklist ("2/4 fournis") côté client, mais un document d'un
 * type hors-liste peut quand même être rattaché — pas de validation stricte
 * côté serveur.
 */
@Entity
@Table(name = "projets")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Projet
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Le nom du projet est obligatoire")
    @Column(nullable = false, length = 150)
    private String nom;

    @Column(length = 1000)
    private String description;

    /** UO propriétaire du projet — détermine qui est notifié à la création. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uo_id", nullable = false)
    @JsonIgnore
    private UniteOrganisationnelle uniteOrganisationnelle;

    /**
     * PUBLIC (défaut) ou PRIVÉ — même mécanique que Document.access. Un projet
     * PRIVÉ a un GroupeAccess (ci-dessous) ; tout document versé dedans hérite
     * automatiquement de cette confidentialité et du MÊME groupe (voir
     * DocumentUploadeService) — jamais un groupe recréé par document.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeAccess access = TypeAccess.PUBLIC;

    /**
     * Groupe d'accès du projet — non nul seulement si access == PRIVE. Le
     * créateur (creePar) en est le propriétaire : seul lui peut ajouter/retirer
     * des membres (voir ProjetService), et il ne peut jamais s'en retirer
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

    // @JsonIgnore : évite le cycle Projet → documents → Document → projet → ...
    @OneToMany(mappedBy = "projet", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Document> documents;

    /**
     * Modèle de dossier — types de documents attendus (informatif, voir
     * Javadoc de la classe). Pas de mappedBy inverse sur TypeDocument, donc
     * pas de risque de cycle de sérialisation ici.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "projet_types_documents_attendus",
        joinColumns = @JoinColumn(name = "projet_id"),
        inverseJoinColumns = @JoinColumn(name = "type_document_id")
    )
    private List<TypeDocument> typesDocumentsAttendus;
}
