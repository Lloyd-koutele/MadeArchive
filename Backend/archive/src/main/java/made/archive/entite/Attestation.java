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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Attestation d'archivage — un jeton PUBLIC (pas l'UUID réel du document) qui
 * donne accès en lecture seule + téléchargement au PDF/A d'un document,
 * sans jamais changer son statut d'accès (PUBLIC/PRIVÉ) ni ses droits
 * normaux. Un seul document produit au plus une seule attestation (jeton
 * stable réutilisé à chaque nouvelle demande de génération — voir
 * AttestationService.genererOuRecuperer) ; le PDF lui-même est reconstruit
 * à la volée à chaque consultation publique, jamais stocké (voir
 * AttestationPdfService), donc rien à régénérer si un jour la présentation
 * change.
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

    @OneToOne
    @JoinColumn(name = "document_id", nullable = false, unique = true)
    @JsonIgnore
    private Document document;

    @ManyToOne
    @JoinColumn(name = "genere_par_id")
    @JsonIgnore
    private User generePar;

    @Column(nullable = false)
    private LocalDateTime genereLe;
}
