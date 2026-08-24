package made.archive.entite;

import java.time.Instant;
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
     * compte, changement de rôle, changement de mot de passe). Tout JWT émis
     * AVANT cet instant est rejeté par {@link made.archive.security.JwtAuthFilter},
     * même s'il n'est pas encore expiré — cela force une reconnexion.
     */
    private Instant sessionInvalidatedAt;

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