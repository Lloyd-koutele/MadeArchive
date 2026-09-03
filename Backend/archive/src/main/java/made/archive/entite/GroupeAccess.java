package made.archive.entite;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.Column;

import com.fasterxml.jackson.annotation.JsonIgnore;


@Entity
@Table(name = "groupe_access")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GroupeAccess
{
   @Id
   @GeneratedValue
   private Long id;

   // Pas de nom — un GroupeAccess n'est identifié que par son id, jamais
   // affiché ni utilisé nulle part (le document/projet auquel il est
   // rattaché a déjà son propre nom ; voir GestionGroupe.tsx côté client,
   // qui ne montre jamais que la liste des MEMBRES).

   // @JsonIgnore : évite le cycle GroupeAccess → documents → Document → groupe → ...
   @OneToMany(mappedBy = "groupe", fetch = FetchType.LAZY)
   @JsonIgnore
   private List<Document> documents;

   @ManyToMany
   @JoinTable(name = "groupe_membres",
    joinColumns = @JoinColumn(name = "groupe_id"),
    inverseJoinColumns = @JoinColumn(name = "user_id"))
   private List<User> membres;

   @Column(nullable = false, length = 30)
   private LocalDate createAt;
}
