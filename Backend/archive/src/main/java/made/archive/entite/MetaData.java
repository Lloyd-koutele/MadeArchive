package made.archive.entite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name="meta_datas")
@Entity
public class MetaData
{
    @Id
    @GeneratedValue
    private Long id;

    @NotBlank(message = "Le nom de la métadonnée est obligatoire")
    @Column(nullable = false, length = 100)
    private String nom;

    @NotNull
    @Column(nullable = false)
    private Boolean obligatoire;

    // Regex générée par Qwen au premier document — nullable jusqu'alors
    @Column(length = 500)
    private String extractionRegex;

    // EqualsAndHashCode.Exclude + ToString.Exclude : @Data génère equals()/hashCode()/toString()
    // sur TOUS les champs par défaut — sans ça, comparer un MetaData compare aussi son
    // TypeDocument parent, qui compare à son tour sa liste de MetaData... récursion infinie
    // dès qu'Hibernate doit comparer les éléments de la collection un par un (ce qui arrive
    // précisément quand sa taille change, ex. ajout d'un champ — voir TypeDocument.metaData).
    @ManyToOne
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JoinColumn(name = "type_document_id", nullable = false)
    private TypeDocument typeDocument;
}