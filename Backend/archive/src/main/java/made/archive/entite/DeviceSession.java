package made.archive.entite;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Column;

@Entity
@Table(name = "device_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceSession 
{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(nullable = false, unique = true)
    private String refreshToken;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    /** Identifiant commun à tous les jetons successifs d'une même connexion (un jeton par renouvellement). */
    @Column(length = 64)
    private String familleId;

    /** Fin de vie maximale de la connexion : aucun renouvellement ne la dépasse. */
    private Instant expirationAbsolue;

    /** Création de CE jeton (sert à savoir si une invalidation de session lui est postérieure). */
    private Instant creeLe;

    /** Renseigné quand ce jeton a été échangé contre un successeur ; il ne peut plus servir qu'un court instant. */
    private Instant rotatedAt;

    /** Jeton de rafraîchissement qui a remplacé celui-ci lors du dernier échange. */
    @Column(length = 255)
    private String successeur;
}