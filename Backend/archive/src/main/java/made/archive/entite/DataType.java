package made.archive.entite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import jakarta.persistence.GeneratedValue;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name="data_types")
public class DataType
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // TEXT plutôt qu'une limite fixe : ce champ stocke la valeur de n'importe quelle
    // métadonnée de texte libre (ex. "Sujet" d'un mémoire) — 50 caractères était bien
    // trop court, une seule phrase suffit à le dépasser, et rien ne garantit qu'une
    // limite numérique plus généreuse suffirait toujours non plus.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String valeur;

    // @JsonIgnore : évite le cycle DataType → document → data → DataType → ...
    @ManyToOne
    @JoinColumn(name = "document_id")
    @JsonIgnore
    private Document document;

    // Nullable : les DataType créés avant l'ajout de ce champ n'ont pas de libellé
    // connu (impossible de le déduire rétroactivement) — leur valeur reste affichée
    // sans label plutôt que de bloquer l'affichage. Voir DocumentUploadeService
    // .validateAndBuildDataTypes(), seul point de création, qui résout déjà le
    // MetaData correspondant avant cette modification (il était résolu puis jeté).
    @ManyToOne
    @JoinColumn(name = "meta_data_id")
    @JsonIgnore
    private MetaData metaData;
}
