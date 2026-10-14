package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration du magasin de clés de signature des éditeurs, de la clé système et de la clé de chaînage du journal.
 *
 * Deux modes (hsm.type) :
 *   PKCS11 (déploiement) : un jeton SoftHSM, un HSM logiciel libre qui parle l'interface standard PKCS#11. Les clés y
 *     sont générées À L'INTÉRIEUR du jeton, non exportables : la clé privée ne passe jamais par la mémoire de l'application.
 *   PKCS12 (poste de développement, tests) : un fichier keystore PKCS#12, quand SoftHSM n'est pas installé.
 *
 * Les secrets (code PIN, mot de passe du fichier) se fournissent par variable d'environnement (.env), jamais en dur.
 */
@Data
@Component
@ConfigurationProperties(prefix = "hsm")
public class HsmProperties
{
    /** PKCS11 (SoftHSM) ou PKCS12 (fichier). */
    private String type = "PKCS12";

    // ── Mode PKCS11 (SoftHSM) ───────────────────────────────────────────────────────────────────────────────────
    /** Bibliothèque PKCS#11 de SoftHSM. */
    private String pkcs11Library = "/usr/lib/softhsm/libsofthsm2.so";

    /** Rang du jeton dans la liste des slots (SoftHSM place les jetons initialisés en premier : 0 avec un seul jeton). */
    private int pkcs11SlotListIndex = 0;

    /** Code PIN utilisateur du jeton. */
    private String pin;

    // ── Mode PKCS12 (fichier) ───────────────────────────────────────────────────────────────────────────────────
    /** Chemin du fichier KeyStore PKCS12. */
    private String keystorePath;

    /** Mot de passe protégeant le KeyStore et chaque entrée de clé privée qu'il contient. */
    private String keystorePassword;

    // ── Communs ─────────────────────────────────────────────────────────────────────────────────────────────────
    /** Nom distinctif utilisé pour le certificat auto-signé enveloppant chaque clé publique. */
    private String certificateDn = "CN=MadeArchive Editor Signing Key, O=MadeArchive";

    /** Durée de validité (en années) du certificat auto-signé généré pour chaque clé. */
    private int certificateValidityYears = 10;

    public boolean estPkcs11()
    {
        return "PKCS11".equalsIgnoreCase(type);
    }
}
