package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Propriétés générales de l'application.
 *
 * appDomain est le NOM DE DOMAINE nu (ex. "madearchive.sn"), SANS schéma ni
 * chemin — délibérément, pour être exactement la même valeur que celle
 * attendue par traefik/dynamic.yml.template (Host(`...`)), qui n'accepte pas
 * non plus de schéma. Avant, cette propriété (alors "frontendUrl") stockait
 * une URL complète ("https://...") dupliquée séparément dans dynamic.yml —
 * deux copies manuelles du même domaine, sans lien entre elles, à l'origine
 * d'un vrai bug (une différait de l'autre par une coquille, rejet CORS en
 * 403). Un seul appDomain, lu depuis .env par les DEUX côtés (Spring Boot ici,
 * traefik/docker-entrypoint.sh côté Traefik), élimine cette classe de bug.
 * Le "https://" est ajouté par le code qui construit une URL complète
 * (SecurityConfig, AttestationService) — jamais stocké ici.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties
{
    private String appDomain;

    // Origines CORS supplémentaires (en plus de appDomain et des ports Vite
    // locaux, voir SecurityConfig), séparées par des virgules — pour tester
    // depuis un autre appareil du réseau local (ex: téléphone via une IP:port
    // qui n'est pas le domaine canonique) sans avoir à reconstruire l'image à
    // chaque fois. Distinct de appDomain : celui-ci reste LE domaine canonique
    // unique utilisé dans les liens de QR code (AttestationService) et pour
    // Traefik — jamais une liste.
    private String corsAdditionalOrigins;
}
