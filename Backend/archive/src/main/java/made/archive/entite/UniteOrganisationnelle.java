package made.archive.entite;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "unites_organisationnelles")
@NoArgsConstructor
@AllArgsConstructor
public class UniteOrganisationnelle 
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nom;

    @ManyToOne
    @JoinColumn(name = "parent_id")
    private UniteOrganisationnelle parent;

    @OneToMany(mappedBy = "parent")
    @JsonIgnore
    private List<UniteOrganisationnelle> children;

    @OneToMany(mappedBy = "uniteOrganisationnelle")
    private List<TypeDocument> typeDocuments;

    @OneToMany(mappedBy = "uniteOrganisationnelle")
    @JsonIgnore
    private List<MembreUniteOrganisationnelle> membres;
}
