package made.archive.entite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;


@Entity
@Table(name="retentions")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Retention
{
    @Id
    @GeneratedValue
    private Long id;

    private Long retentionYears;

    private Long periodGrace;

    @NotNull
    @Column(nullable = false)
    private LocalDateTime createAt = LocalDateTime.now();

    /**
     * Décision de sort final — voir SortFinal pour la sémantique complète.
     * CONSERVER par défaut (@ColumnDefault, pas seulement l'initialiseur Java
     * ci-dessous invisible du schéma) : backfill sûr des règles existantes à
     * l'introduction de ce champ — aucune règle déjà en place ne devient
     * éligible à une purge automatique sans décision explicite d'un éditeur.
     */
    @NotNull
    @Column(name = "sort_final", nullable = false, length = 20)
    @ColumnDefault("'CONSERVER'")
    @Enumerated(EnumType.STRING)
    private SortFinal sortFinal = SortFinal.CONSERVER;
}
