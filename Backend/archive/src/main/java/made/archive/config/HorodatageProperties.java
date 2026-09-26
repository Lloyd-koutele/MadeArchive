package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Autorité d'horodatage (TSA, RFC 3161) utilisée pour horodater le hash
 * PDF/A de chaque document archivé — voir HorodatageService.
 *
 * DEUX emplacements INDÉPENDANTS, configurables via .env :
 *   - tsaUrl (gratuit) — TOUJOURS disponible, FreeTSA.org par défaut. Pas un
 *     TSA "qualifié" au sens légal (eIDAS), suffisant pour démontrer le
 *     mécanisme.
 *   - tsaUrlPayant + username/password (payant, OPTIONNEL) — pour une valeur
 *     probante réellement opposable (ex. Universign, Certum, SafeCreative).
 *     Authentification HTTP Basic si username/password renseignés (couvre la
 *     majorité des offres commerciales) ; sinon la requête part sans
 *     en-tête d'authentification, exactement comme pour le gratuit.
 *
 * Priorité : le PAYANT l'emporte dès que tsaUrlPayant est renseignée — le
 * gratuit ne sert alors plus du tout, même en cas d'échec du payant (voir
 * urlActive() ci-dessous) : jamais de repli silencieux d'un TSA
 * qualifié vers un TSA non-qualifié, qui changerait la valeur légale du jeton
 * produit sans que personne ne s'en aperçoive. Un payant cassé (abonnement
 * impayé, quota dépassé, source coupée par le fournisseur...) reste donc
 * visiblement sans jeton — jamais masqué par un remplacement silencieux —
 * et alerte l'administration (voir HorodatageService.alerterAdminHorodatageInvalide).
 */
@Data
@Component
@ConfigurationProperties(prefix = "horodatage")
public class HorodatageProperties
{
    private String tsaUrl = "http://freetsa.org/tsr";
    private String tsaUrlPayant;
    private String username;
    private String password;

    public boolean payantConfigure()
    {
        return StringUtils.hasText(tsaUrlPayant);
    }

    public boolean authentificationConfiguree()
    {
        return StringUtils.hasText(username) && StringUtils.hasText(password);
    }

    /** URL effectivement utilisée pour le prochain appel — voir Javadoc de classe (priorité). */
    public String urlActive()
    {
        return payantConfigure() ? tsaUrlPayant : tsaUrl;
    }
}
