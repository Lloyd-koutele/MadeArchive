package made.archive.entite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Un scellement = un passage du job nocturne de chaînage (voir
 * service.audit.AuditChainService) qui a trouvé au moins une nouvelle entrée à
 * chaîner — capture l'état de la chaîne à cet instant (dernière entrée, dernière
 * empreinte) et la preuve tierce RFC 3161 que cette empreinte existait déjà à
 * cette date (même TSA que HorodatageService, réutilisé tel quel).
 */
@Entity
@Table(name = "audit_chain_seals")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditChainSeal
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dernier_entry_id")
    private Long dernierEntryId;

    @Column(name = "dernier_chain_hash", length = 64)
    private String dernierChainHash;

    /** Null si l'horodatage RFC 3161 a échoué à ce passage — best-effort, comme
     *  partout ailleurs (voir HorodatageService) : le chaînage lui-même a quand
     *  même eu lieu, seule la preuve tierce externe manque pour ce scellement. */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "horodatage_token")
    private byte[] horodatageToken;

    @Column(name = "horodatage_date")
    private Instant horodatageDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
