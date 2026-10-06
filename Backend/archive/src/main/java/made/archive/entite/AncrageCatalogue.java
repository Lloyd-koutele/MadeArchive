package made.archive.entite;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Un ancrage du catalogue : la racine de Merkle de tous les documents scellés arrivés depuis le précédent
 * ancrage, signée par la clé système et horodatée par une autorité tierce (RFC 3161), éventuellement aussi
 * inscrite sur une blockchain. Une fois le jeton obtenu, plus aucune modification des empreintes de ces
 * documents n'est possible sans que la racine recalculée diverge de celle que le tiers a certifiée.
 *
 * Ajout seul (déclencheur en base) : seuls le jeton, sa date et la transaction blockchain se complètent après coup.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ancrages_catalogue")
public class AncrageCatalogue
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "nombre_documents", nullable = false)
    private int nombreDocuments;

    @Column(name = "racine_merkle", nullable = false, length = 64)
    private String racineMerkle;

    /** Racine du précédent ancrage — chaîne les ancrages entre eux (en supprimer un en plein milieu se voit). */
    @Column(name = "racine_precedente", length = 64)
    private String racinePrecedente;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String signature;

    @Column(name = "signature_alias", nullable = false, length = 100)
    private String signatureAlias;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "horodatage_token")
    private byte[] horodatageToken;

    @Column(name = "horodatage_date")
    private Instant horodatageDate;

    /** Transaction blockchain (optionnelle, voir ancrage.blockchain.actif). */
    @Column(name = "blockchain_tx", length = 400)
    private String blockchainTx;
}
