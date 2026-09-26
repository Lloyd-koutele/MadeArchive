package made.archive.entite;

import java.time.LocalDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Attestation d'archivage — un jeton PUBLIC (pas l'UUID réel du document) qui
 * donne accès en lecture seule + téléchargement au PDF/A d'un document, sans
 * jamais changer son statut d'accès (PUBLIC/PRIVÉ) ni ses droits normaux. Un
 * document peut avoir PLUSIEURS attestations actives simultanément — chaque
 * demande (voir AttestationService.genererNouvelle) crée un tout nouveau
 * jeton indépendant, jamais de réutilisation ; utile par exemple pour donner
 * un lien séparé à chaque destinataire externe, révocable indépendamment des
 * autres. Le PDF lui-même est reconstruit à la volée à chaque consultation
 * publique, jamais stocké (voir AttestationPdfService).
 *
 * Durée de vie : {@link #expireLe} (2 jours après {@link #genereLe}), propre
 * à CHAQUE jeton — passé ce délai, le jeton est refusé côté consultation
 * publique et purgé par une tâche planifiée (voir
 * AttestationExpirationScheduler), avec trace dans le journal d'audit avant
 * suppression. Si le document passe de PUBLIC à PRIVÉ (voir
 * DocumentService.modifierAcces), TOUTES ses attestations actives sont
 * purgées immédiatement, sans attendre leur expiration — l'inverse (PRIVÉ →
 * PUBLIC) ne change rien aux attestations existantes, qui continuent chacune
 * leur propre délai.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "attestations")
public class Attestation
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Jeton opaque exposé publiquement (URL/QR) — jamais l'UUID réel du
    // document, pour ne pas rendre son identifiant interne devinable/partagé.
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @ManyToOne
    @JoinColumn(name = "document_id", nullable = false)
    @JsonIgnore
    private Document document;

    @ManyToOne
    @JoinColumn(name = "genere_par_id")
    @JsonIgnore
    private User generePar;

    @Column(nullable = false)
    private LocalDateTime genereLe;

    // 2 jours après genereLe — voir Javadoc de la classe.
    @Column(nullable = false)
    private LocalDateTime expireLe;
}
