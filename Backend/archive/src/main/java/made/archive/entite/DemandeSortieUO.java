package made.archive.entite;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "demandes_sortie_uo")
@NoArgsConstructor
@AllArgsConstructor
public class DemandeSortieUO 
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private User demandeur;

    @ManyToOne(optional = false)
    @JoinColumn(name = "unite_id")
    private UniteOrganisationnelle unite;

    @Enumerated(EnumType.STRING)
    private StatutDemande statut;

    private LocalDateTime dateDemande;

    @ManyToOne
    @JoinColumn(name = "traite_par_id")
    private User traitePar;

    private LocalDateTime dateTraitement;
}