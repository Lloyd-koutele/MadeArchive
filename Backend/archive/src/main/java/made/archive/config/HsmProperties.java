package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration du "HSM fichier" : un KeyStore PKCS12 qui émule un HSM logiciel
 * pour la conservation des clés privées PKI des utilisateurs éditeurs (rôle EDITOR).
 *
 * Le fichier ne doit jamais être versionné (voir .gitignore) et son mot de passe
 * doit être fourni via variable d'environnement (.env), jamais en dur.
 */
@Data
@Component
@ConfigurationProperties(prefix = "hsm")
public class HsmProperties
{
    /** Chemin du fichier KeyStore PKCS12 servant de HSM logiciel. */
    private String keystorePath;

    /** Mot de passe protégeant le KeyStore et chaque entrée de clé privée qu'il contient. */
    private String keystorePassword;

    /** Nom distinctif utilisé pour le certificat auto-signé enveloppant chaque clé publique. */
    private String certificateDn = "CN=MadeArchive Editor Signing Key, O=MadeArchive";

    /** Durée de validité (en années) du certificat auto-signé généré pour chaque clé. */
    private int certificateValidityYears = 10;
}
