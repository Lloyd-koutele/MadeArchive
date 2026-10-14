package made.archive.config;

import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Dossier persistant, HORS base de données, des preuves qui ne sont pas des clés : le registre des scellements du
 * journal d'audit et les empreintes de certificats de TSA (voir RegistreScellementsService et TsaAncreService).
 * Les clés, elles, vivent dans le jeton SoftHSM (voir HsmKeyStoreService), pas ici.
 * En déploiement les empreintes de TSA ont leur propre volume (horodatage.ancres-path, HORODATAGE_ANCRES_PATH) ; ce dossier
 * ne garde alors que le registre des scellements.
 *
 * Configuré par {@code preuves.dossier} (PREUVES_DOSSIER) — un volume dédié en déploiement. À défaut, et seulement
 * en repli de développement (HSM_TYPE=PKCS12), le dossier du fichier keystore : l'ancien emplacement.
 */
@Component
public class DossierPreuves
{
    private final String dossier;
    private final HsmProperties hsmProperties;

    public DossierPreuves(@Value("${preuves.dossier:}") String dossier, HsmProperties hsmProperties)
    {
        this.dossier = dossier;
        this.hsmProperties = hsmProperties;
    }

    /** Chemin d'un fichier de preuves, ou vide si aucun emplacement n'est configuré. */
    public Optional<Path> fichier(String nom)
    {
        if (StringUtils.hasText(dossier))
        {
            return Optional.of(Path.of(dossier).resolve(nom));
        }
        if (StringUtils.hasText(hsmProperties.getKeystorePath()))
        {
            return Optional.of(Path.of(hsmProperties.getKeystorePath()).resolveSibling(nom));
        }
        return Optional.empty();
    }
}
