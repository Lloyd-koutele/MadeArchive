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

   @Column(nullable = false, length = 100)
   private String nom;

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
