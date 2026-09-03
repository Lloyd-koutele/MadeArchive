package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration du navigateur headless (Chromium, piloté via Playwright)
 * utilisé en repli par l'import via lien web — voir
 * HeadlessBrowserImportService.
 *
 * Chromium tourne dans un conteneur Docker dédié, séparé de "app" (image
 * ghcr.io/browserless/chromium, voir docker-compose.yml), piloté à distance
 * via son point d'entrée WebSocket pensé pour Playwright (wsEndpoint) —
 * isolation délibérée : si Chromium consomme trop de mémoire ou plante, seul
 * ce conteneur redémarre, jamais "app". Une image dédiée est nécessaire ici
 * (pas un simple Chromium + relais réseau) : les versions récentes de
 * Chromium refusent toute connexion CDP distante quel que soit
 * --remote-debugging-address (durcissement sécurité volontaire, constaté en
 * le testant directement).
 *
 * En plus de cette isolation par conteneur, maxConcurrent borne le nombre
 * d'onglets ouverts en même temps depuis "app" (défense en profondeur, pas
 * une confiance aveugle dans les limites mémoire/CPU du conteneur Chromium) ;
 * au-delà, les demandes en surplus attendent un court instant puis échouent
 * proprement plutôt que de s'empiler indéfiniment.
 */
@Data
@Component
@ConfigurationProperties(prefix = "web-import.headless")
public class WebImportHeadlessProperties
{
    // Port 3001, pas 3000 : le conteneur chromium est mappé sur 3001 côté hôte
    // dans docker-compose.yml (3000 est déjà pris par gotenberg) — un
    // développement local (backend hors Docker) qui lance quand même le
    // conteneur chromium via "docker compose up chromium" doit le joindre sur
    // ce même port. Fonctionne sans jeton uniquement si TOKEN n'est pas
    // défini sur ce conteneur local — sinon, surcharger via
    // CHROMIUM_WS_ENDPOINT avec "?token=..." ajouté.
    private String wsEndpoint          = "ws://localhost:3001/playwright/chromium";
    private int    maxConcurrent       = 3;
    private int    attenteSlotSecondes = 10;
}
