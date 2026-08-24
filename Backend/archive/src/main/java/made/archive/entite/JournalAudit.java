package made.archive.entite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Une ligne = une action journalisée pour l'audit du système (voir AuditLogService).
 *
 * Délibérément SANS relation JPA (@ManyToOne) vers User/Document/UO : l'acteur et la
 * cible sont capturés en snapshot (id + libellé lisible au moment de l'action). Une
 * ligne d'audit doit survivre à la suppression de l'entité qu'elle décrit, et ne doit
 * jamais déclencher de chargement paresseux coûteux lors d'un simple listage.
 */
@Entity
@Table(name = "journal_audit", indexes = {
    @Index(name = "idx_audit_horodatage", columnList = "horodatage"),
    @Index(name = "idx_audit_acteur", columnList = "acteurId"),
    @Index(name = "idx_audit_uo", columnList = "uoId"),
    @Index(name = "idx_audit_action", columnList = "action"),
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JournalAudit
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant horodatage;

    /** Null pour une action anonyme (ex. vérification publique d'authenticité). */
    private UUID acteurId;

    /** Snapshot — reste lisible même si l'utilisateur est ensuite modifié/supprimé. */
    @Column(length = 150)
    private String acteurEmail;

    @Column(length = 100)
    private String acteurRole;

    @Column(length = 45)
    private String adresseIp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private AuditCible cibleType;

    /** Générique (UUID pour Document/Utilisateur, Long pour UO/TypeDocument/Projet). */
    @Column(length = 60)
    private String cibleId;

    /**
     * Contexte organisationnel de l'action — utilisé pour restreindre la consultation
     * du journal aux ADMIN_UO (ils ne voient que ce qui concerne leur UO et son
     * sous-arbre). Peut être null pour une action hors contexte UO (ex. login).
     */
    private Long uoId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean succes;

    /** JSON optionnel — ex. { "avant": "USER", "apres": "EDITOR" } pour un changement de rôle. */
    @Column(columnDefinition = "TEXT")
    private String details;
}
