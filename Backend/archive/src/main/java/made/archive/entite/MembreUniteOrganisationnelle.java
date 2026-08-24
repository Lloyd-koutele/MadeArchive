package made.archive.entite;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "membres_uo")
@NoArgsConstructor
@AllArgsConstructor
public class MembreUniteOrganisationnelle 
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "unite_id")
    private UniteOrganisationnelle uniteOrganisationnelle;

    private LocalDateTime dateAjout;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ajoute_par_id")
    private User ajoutePar;

    private Boolean actif = true;

    private LocalDateTime dateRetrait;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "retire_par_id")
    private User retirePar;
}