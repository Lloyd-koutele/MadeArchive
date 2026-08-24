package made.archive.entite;

import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "fixity_check_results")
public class FixityCheckResult
{
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // @JsonIgnore : évite le cycle FixityCheckResult → document → fixityCheckResult → ...
    @OneToOne
    @JoinColumn(name="document_id", nullable=false, unique=true)
    @JsonIgnore
    private Document document;

    @Column(nullable = false)
    private LocalDate checkedAt;


    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private CheckResult result;

    /**
     * Explication lisible de ce qui a déclenché CORRUPTED/EMPTY (empreinte SHA-256
     * différente, échec de déchiffrement, fichier introuvable dans le stockage...) —
     * affichée à côté du document pour l'éditeur/l'admin. Null quand result == OK.
     */
    @Column(columnDefinition = "TEXT")
    private String raison;
}
