package made.archive.entite;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "users")
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "user_type", discriminatorType = DiscriminatorType.STRING)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User 
{

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank(message = "Le nom est obligatoire")
    @Size(min = 2, max = 50, message = "Le nom doit contenir entre 2 et 50 caractères")
    @Column(nullable = false, length = 50)
    private String nom;

    @NotBlank(message = "Le prénom est obligatoire")
    @Size(min = 2, max = 50, message = "Le prénom doit contenir entre 2 et 50 caractères")
    @Column(nullable = false, length = 50)
    private String prenom;

    @NotBlank(message = "L'email est obligatoire")
    @Email(message = "L'email doit être valide")
    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    private String password;

    @Column(nullable = false)
    private Boolean actif = true;

    @NotBlank(message = "Le numéro de téléphone est obligatoire")
    @Column(nullable = false, length = 100, unique=true)
    private String telephone;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_role",
            joinColumns = @JoinColumn(
                    name = "user_id", 
                    referencedColumnName = "id" 
            ),
            inverseJoinColumns = @JoinColumn(
                    name = "role_id", 
                    referencedColumnName = "id"
            )
    )
    private Set<Role> roles = new HashSet<>();

    public Boolean isActif()
    {
        return this.actif;
    }

    /**
     * Horodatage de la dernière invalidation forcée de session (blocage du
     * compte, changement de rôle, changement de mot de passe, transfert
     * d'UO). Tout JWT émis AVANT cet instant est rejeté par
     * {@link made.archive.security.JwtAuthFilter}, même s'il n'est pas
     * encore expiré — cela force une reconnexion.
     */
    private Instant sessionInvalidatedAt;

    /**
     * Pourquoi la session a été invalidée (voir sessionInvalidatedAt) — permet à
     * JwtAuthFilter de renvoyer un message adapté au client ("UO_CHANGEE",
     * "SESSION_INVALIDATED"...) plutôt qu'un message générique unique. Le blocage
     * de compte n'a pas besoin de cette valeur : "ACCOUNT_BLOCKED" est déterminé
     * dynamiquement à partir de actif (voir JwtAuthFilter.resolveFailureReason),
     * pas stocké ici. Peut rester null (invalidations existantes avant l'ajout de
     * ce champ, ou rôle/mot de passe changé) — le filtre retombe alors sur
     * "SESSION_INVALIDATED".
     */
    private String sessionInvalidationReason;

    /**
     * Date à partir de laquelle une suppression DEMANDÉE devient exécutable — voir
     * UserService.demanderSuppression. Délai de grâce de 2 jours (raison de
     * sécurité : un ADMIN malveillant ou dont le compte est compromis ne doit pas
     * pouvoir détruire une identité de façon instantanée et irréversible — un autre
     * ADMIN a le temps de voir la suppression en attente et de l'annuler, voir
     * UserService.annulerSuppression). Non-null = suppression en attente ; le compte
     * est immédiatement bloqué (actif=false, session invalidée) dès la demande —
     * seule la partie IRRÉVERSIBLE (mot de passe invalidé pour de bon, clé PKI
     * révoquée, ou DELETE réel si le compte n'a jamais servi) attend ce délai,
     * exécutée par UserSuppressionCleanupScheduler. Remis à null si annulée, ou si
     * le compte est déjà supprimé pour de bon (supprimeLe non-null).
     */
    private LocalDate suppressionPrevueLe;

    /**
     * Horodatage d'une suppression LOGIQUE déjà EXÉCUTÉE (compte qui avait déjà
     * servi — voir UserService, méthode appelée par UserSuppressionCleanupScheduler
     * une fois suppressionPrevueLe atteint) — null tant qu'elle ne l'est pas.
     * Contrairement au blocage (actif=false seul, réversible), c'est irréversible :
     * nom/prénom/email/id sont volontairement CONSERVÉS (ils restent lisibles sur
     * les documents/projets/exports déjà réalisés par ce compte, et dans le journal
     * d'audit), seuls le mot de passe (remplacé par une valeur aléatoire) et la clé
     * PKI (révoquée si active) sont coupés. Un compte jamais connecté est supprimé
     * pour de vrai (DELETE réel) à la place — ce champ ne concerne donc que le cas
     * "a déjà servi".
     */
    private Instant supprimeLe;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<TypeDocument> typeDocuments;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<MembreUniteOrganisationnelle> membresUniteOrganisationnelles;

    // ── PKI (signature de documents, rôle EDITOR) ──────────────────────────
    // La clé privée n'est JAMAIS stockée ici : elle réside uniquement dans le
    // HSM fichier (voir made.archive.security.HsmKeyStoreService), référencée
    // par pkiKeyAlias. Seule la clé publique est conservée en base.

    /** Alias de la clé privée de cet utilisateur dans le HSM fichier (KeyStore PKCS12). */
    @Column(length = 100)
    private String pkiKeyAlias;

    /** Clé publique RSA (PEM) correspondant à la clé privée déposée dans le HSM fichier. */
    @Column(columnDefinition = "TEXT")
    private String pkiPublicKey;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PkiKeyStatus pkiKeyStatus = PkiKeyStatus.NONE;

    private LocalDateTime pkiKeyCreatedAt;
}