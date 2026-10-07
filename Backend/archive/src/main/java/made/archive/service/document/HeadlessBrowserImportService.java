package made.archive.service.document;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.WebImportHeadlessProperties;
import made.archive.exception.BusinessException;
import made.archive.service.importweb.AdressesInterdites;
import made.archive.service.importweb.ClientHttpSecurise;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Repli "navigateur headless" pour l'import via lien web (WebImportService), utilisé uniquement quand un lien dépend du
 * JavaScript pour afficher son contenu — ce qu'un simple appel HTTP + parsing HTML statique (Jsoup) ne peut pas voir :
 * {@link #rendreEtRecupererHtml} rend la page une fois, puis le même parsing de liens que le mode "page" s'applique au
 * HTML obtenu après rendu. Aucun fichier n'est téléchargé ici et rien n'est jamais décompressé : les fichiers découverts
 * sont récupérés ensuite par le client HTTP sécurisé (ClientHttpSecurise), qui revalide chaque adresse.
 *
 * Protection anti-SSRF, en deux couches indépendantes — la page rendue est du code d'un tiers qui s'exécute :
 *  1. Chaque requête réseau du navigateur (chargement de scripts, images, appels XHR/fetch, redirections internes à la
 *     page) est INTERCEPTÉE ici et refusée si elle vise une adresse interne, privée ou locale (AdressesInterdites), ou un
 *     schéma autre que http/https (voir {@link #nouveauContexte}).
 *  2. Le conteneur Chromium est isolé sur un réseau Docker dédié (docker-compose.yml) qui ne voit ni MinIO, ni
 *     PostgreSQL, ni Redis, ni Meilisearch, ni Ollama : même si la couche 1 était contournée (rebinding DNS dans le
 *     navigateur, par exemple), il n'y a rien d'interne à atteindre.
 *
 * Limites assumées :
 *  - Ne fonctionne que pour du contenu PUBLIC — aucune solution ici ne peut franchir une connexion privée.
 *  - Aucune tentative de résolution de CAPTCHA ou de contournement d'un éventuel challenge anti-bot.
 *  - Chromium tourne dans un conteneur Docker dédié (image ghcr.io/browserless/chromium), piloté à distance via son point
 *    d'entrée WebSocket pensé pour Playwright — PAS lancé dans le processus de l'application. Isolation délibérée : un
 *    onglet consomme réellement de la mémoire ; si Chromium plante ou consomme trop, seul son conteneur (mémoire/CPU
 *    plafonnés) en subit les conséquences. {@link WebImportHeadlessProperties} borne en plus le nombre d'onglets
 *    ouverts en même temps depuis "app" (3 par défaut).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeadlessBrowserImportService
{
    private static final Duration TIMEOUT_NAVIGATION  = Duration.ofSeconds(30);

    private final WebImportHeadlessProperties proprietes;

    private Playwright playwright;
    private Browser browser;
    private Semaphore permis;

    @PostConstruct
    private void initialiserPermis()
    {
        permis = new Semaphore(Math.max(1, proprietes.getMaxConcurrent()));
    }

    // ═══════════════════════════════════════════════════════════════
    // Repli générique — page rendue (JS exécuté), pour re-scraping
    // ═══════════════════════════════════════════════════════════════

    /** Rend une page (JS exécuté) et retourne son HTML final, ou null en cas d'échec/de saturation (best-effort). */
    public String rendreEtRecupererHtml(URI uri)
    {
        if (!acquerirPermis())
        {
            log.warn("[HeadlessImport] Repli générique ignoré (trop de demandes en cours) : {}", uri);
            return null;
        }

        try (BrowserContext contexte = ouvrirContexte())
        {
            Page page = contexte.newPage();
            page.navigate(uri.toString(), new Page.NavigateOptions()
                .setTimeout(TIMEOUT_NAVIGATION.toMillis())
                .setWaitUntil(WaitUntilState.NETWORKIDLE));
            return page.content();
        }
        catch (Exception e)
        {
            log.warn("[HeadlessImport] Échec du rendu de la page {} : {}", uri, e.getMessage());
            return null;
        }
        finally
        {
            permis.release();
        }
    }

    /** Attend un "slot" libre (max proprietes.maxConcurrent en même temps) jusqu'à attenteSlotSecondes, sinon refuse. */
    private boolean acquerirPermis()
    {
        try
        {
            return permis.tryAcquire(proprietes.getAttenteSlotSecondes(), TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers privés
    // ═══════════════════════════════════════════════════════════════

    /**
     * Se connecte au conteneur Chromium dédié à la première utilisation (pas
     * au démarrage de l'appli — voir javadoc de classe), et se reconnecte
     * automatiquement une fois si la connexion s'avère rompue (ex. le
     * conteneur Chromium a redémarré depuis, après un OOM contenu dans SES
     * propres limites — c'est précisément l'isolation recherchée).
     */
    private synchronized BrowserContext ouvrirContexte()
    {
        if (browser == null || !browser.isConnected())
        {
            connecter();
        }

        try
        {
            return nouveauContexte();
        }
        catch (Exception e)
        {
            log.warn("[HeadlessImport] Contexte échoué, nouvelle tentative de connexion : {}", e.getMessage());
            connecter();
            return nouveauContexte();
        }
    }

    private BrowserContext nouveauContexte()
    {
        // Aucun téléchargement de fichier par le navigateur : seule la page rendue nous intéresse.
        BrowserContext contexte = browser.newContext(new Browser.NewContextOptions()
            .setLocale("fr-FR")
            .setAcceptDownloads(false));
        contexte.route("**/*", route ->
        {
            String url = route.request().url();
            if (requeteAutorisee(url))
            {
                route.resume();
            }
            else
            {
                log.warn("[HeadlessImport] Requête du navigateur BLOQUÉE (adresse interne ou schéma interdit) : {}",
                    ClientHttpSecurise.hoteDe(url));
                route.abort("blockedbyclient");
            }
        });
        return contexte;
    }

    /**
     * Une requête du navigateur est autorisée si elle ne touche pas le réseau (data:, blob:, about:) ou si elle vise
     * http(s) vers un hôte qui ne résout vers AUCUNE adresse interdite. Visible pour les tests.
     */
    static boolean requeteAutorisee(String url)
    {
        try
        {
            URI uri = URI.create(url);
            String schema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (schema.equals("data") || schema.equals("blob") || schema.equals("about"))
            {
                return true;
            }
            if (!schema.equals("http") && !schema.equals("https"))
            {
                return false;
            }
            return uri.getHost() != null && AdressesInterdites.hoteAutorise(uri.getHost());
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private void connecter()
    {
        try
        {
            if (playwright == null) playwright = Playwright.create();
            // .connect() (protocole serveur Playwright), pas .connectOverCDP() : le
            // CDP brut de Chromium refuse toute connexion distante quel que soit
            // --remote-debugging-address (constaté en le testant) — browserless
            // expose à la place ce point d'entrée WebSocket dédié, prévu et testé
            // pour interopérer avec le client Playwright.
            browser = playwright.chromium().connect(proprietes.getWsEndpoint());
        }
        catch (Exception e)
        {
            log.error("[HeadlessImport] Connexion au conteneur Chromium ({}) impossible : {}",
                proprietes.getWsEndpoint(), e.getMessage());
            throw new BusinessException(
                "Le navigateur headless (conteneur Chromium dédié) est indisponible actuellement.");
        }
    }

    /**
     * Déconnecte le client à l'arrêt de l'application — ne tue PAS le
     * processus Chromium distant (il vit dans son propre conteneur, géré par
     * Docker, pas par ce client).
     */
    @PreDestroy
    public void fermer()
    {
        try { if (browser != null) browser.close(); } catch (Exception ignore) {}
        try { if (playwright != null) playwright.close(); } catch (Exception ignore) {}
    }
}
