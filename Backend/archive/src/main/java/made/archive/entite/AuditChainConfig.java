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
 * Configuration de la chaîne du journal d'audit, écrite UNE fois (ligne unique, id = 1) puis figée par un
 * déclencheur en base. Voir service.audit.AuditChainService et la migration V13.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "audit_chain_config")
public class AuditChainConfig
{
    public static final long ID_UNIQUE = 1L;

    @Id
    private Long id = ID_UNIQUE;

    /** Première position de la chaîne calculée en HMAC ; les positions précédentes sont en SHA-256 simple. */
    @Column(name = "hmac_depuis_position", nullable = false)
    private long hmacDepuisPosition;

    @Column(name = "cle_alias", nullable = false, length = 100)
    private String cleAlias;

    /** HMAC d'un libellé fixe avec la clé : permet de savoir si la clé disponible est bien celle de la chaîne. */
    @Column(name = "cle_empreinte", nullable = false, length = 64)
    private String cleEmpreinte;

    /** Premier scellement qui doit porter une signature ; les précédents datent d'avant son introduction. */
    @Column(name = "scellements_signes_depuis_id", nullable = false)
    private long scellementsSignesDepuisId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
