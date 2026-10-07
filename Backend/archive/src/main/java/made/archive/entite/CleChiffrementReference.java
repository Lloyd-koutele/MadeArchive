package made.archive.entite;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Empreinte de la clé de chiffrement au repos, enregistrée une fois (ligne unique, id = 1). Écriture seule : un
 * déclencheur en base interdit toute modification ou suppression. Voir ControleCleChiffrementService.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cle_chiffrement_reference")
public class CleChiffrementReference
{
    public static final long ID_UNIQUE = 1L;

    @Id
    private Long id = ID_UNIQUE;

    @Column(nullable = false, length = 64)
    private String empreinte;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
