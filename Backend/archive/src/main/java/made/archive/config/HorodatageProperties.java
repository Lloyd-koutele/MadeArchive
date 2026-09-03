package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Autorité d'horodatage (TSA, RFC 3161) utilisée pour horodater le hash
 * PDF/A de chaque document archivé — voir HorodatageService.
 *
 * Par défaut : FreeTSA.org, un service public gratuit, suffisant pour
 * démontrer le mécanisme mais PAS un TSA "qualifié" au sens légal (eIDAS).
 * Pour une valeur probante réellement opposable, remplacer tsaUrl par un
 * TSA accrédité (ex. Universign, DigiCert) — le code n'a pas besoin de
 * changer, seule cette URL.
 */
@Data
@Component
@ConfigurationProperties(prefix = "horodatage")
public class HorodatageProperties
{
    private String tsaUrl = "http://freetsa.org/tsr";
}
