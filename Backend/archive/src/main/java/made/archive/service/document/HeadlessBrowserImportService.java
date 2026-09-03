package made.archive.service.document;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.WebImportHeadlessProperties;
import made.archive.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Repli "navigateur headless" pour l'import via lien web (WebImportService),
 * utilisé uniquement quand un lien dépend du JavaScript pour afficher son
 * contenu — ce qu'un simple appel HTTP + parsing HTML statique (Jsoup) ne
 * peut pas voir. Deux usages :
 *
 *  1. {@link #telechargerDossierDrive} — un dossier Google Drive public
 *     (aucune URL directe n'existe pour "tout le dossier" : on ouvre
 *     réellement la page, on clique "Tout télécharger", on récupère le ZIP
 *     généré dynamiquement par Drive pour ce dossier, et on le dézippe).
 *  2. {@link #rendreEtRecupererHtml} — repli générique pour n'importe quelle
 *     page dont le contenu n'apparaît pas dans le HTML brut (pas spécifique à
 *     Drive) : on rend la page une fois, puis on réutilise le même parsing de
 *     liens que le mode "page" existant sur le HTML obtenu après rendu.
 *
 * Limites assumées (voir discussion avec l'éditeur avant implémentation) :
 *  - Ne fonctionne que pour du contenu PUBLIC (partagé "à toute personne
 *    disposant du lien") — comme le mode HTTP classique, aucune solution ici
 *    ne peut franchir une connexion privée.
 *  - Protection anti-SSRF appliquée seulement à l'URL de départ (déjà validée
 *    par WebImportService.resoudreEtValider avant l'appel) — contrairement au
 *    client HTTP classique, on ne peut pas revalider chaque sous-requête que
 *    le navigateur effectue lui-même en interne (chargement JS, redirections
 *    internes à la page). Risque résiduel accepté : fonctionnalité réservée
 *    aux éditeurs authentifiés, jamais exposée publiquement.
 *  - Aucune tentative de résolution de CAPTCHA ou de contournement d'un
 *    éventuel challenge anti-bot : si la page affiche un challenge au lieu du
 *    contenu attendu, l'opération échoue simplement par timeout (bouton/
 *    contenu jamais trouvé), sans aucune action pour le franchir.
 *  - Chromium tourne dans un conteneur Docker dédié (image
 *    ghcr.io/browserless/chromium, voir docker-compose.yml), piloté ici à
 *    distance via son point d'entrée WebSocket pensé pour Playwright — PAS
 *    lancé localement dans le processus de l'application. Isolation délibérée : un
 *    onglet, même headless, consomme réellement de la mémoire (150-300+ Mo
 *    pour une page aussi lourde que Drive) ; si Chromium consomme trop ou
 *    plante, seul son propre conteneur (mémoire/CPU plafonnés) en subit les
 *    conséquences — jamais "app". En complément (défense en profondeur, pas
 *    une confiance aveugle dans ces limites), {@link WebImportHeadlessProperties}
 *    borne aussi le nombre d'onglets ouverts en même temps depuis "app" (par
 *    défaut 3) — au-delà, les demandes en surplus attendent un court instant
 *    puis échouent proprement (voir {@link #acquerirPermis}) plutôt que de
 *    s'empiler indéfiniment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeadlessBrowserImportService
{
    private static final Duration TIMEOUT_NAVIGATION  = Duration.ofSeconds(30);
    private static final Duration TIMEOUT_TELECHARGEMENT = Duration.ofSeconds(90);

    /**
     * "Tout télécharger" (fr) / "Download all" (en) — Drive change de libellé
     * selon la locale du compte visité. Insensibilité à la casse via le flag
     * CASE_INSENSITIVE, PAS le modificateur inline "(?i)" — Playwright
     * retransmet le texte du motif tel quel au moteur JavaScript du
     * navigateur pour le matching de nom accessible, et JS ne comprend pas
     * "(?i)" comme modificateur inline (contrairement à Java) : ça cassait la
     * sélection avec une erreur de syntaxe, systématiquement, sur tout lien
     * (constaté en reproduisant directement contre un vrai dossier Drive).
     */
    private static final Pattern LIBELLE_TOUT_TELECHARGER =
        Pattern.compile("tout\\s*t[ée]l[ée]charger|download\\s*all", Pattern.CASE_INSENSITIVE);

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
    // 1. Dossier Google Drive public → clic "Tout télécharger" + dézippage
    // ═══════════════════════════════════════════════════════════════

    public List<WebImportService.FichierDistant> telechargerDossierDrive(URI uri)
    {
        if (!acquerirPermis())
        {
            throw new BusinessException(
                "Trop d'imports via navigateur headless en cours sur ce serveur — réessayez dans quelques instants.");
        }

        try (BrowserContext contexte = ouvrirContexte())
        {
            Page page = contexte.newPage();
            page.navigate(uri.toString(), new Page.NavigateOptions()
                .setTimeout(TIMEOUT_NAVIGATION.toMillis())
                .setWaitUntil(WaitUntilState.NETWORKIDLE));

            Locator bouton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(LIBELLE_TOUT_TELECHARGER));
            bouton.waitFor(new Locator.WaitForOptions().setTimeout(TIMEOUT_NAVIGATION.toMillis()));

            Download telechargement = page.waitForDownload(
                new Page.WaitForDownloadOptions().setTimeout(TIMEOUT_TELECHARGEMENT.toMillis()),
                bouton::click);

            // download.path() n'est PAS utilisable ici : ne fonctionne qu'avec un
            // navigateur lancé localement (browser().launch()) — le fichier vivrait
            // sur le disque du conteneur Chromium distant, pas le nôtre. Avec une
            // connexion à distance (connect(), voir ouvrirContexte/connecter),
            // saveAs() est la méthode prévue : elle rapatrie les octets depuis le
            // conteneur distant vers un fichier local via la connexion elle-même
            // (constaté en reproduisant contre un vrai dossier : path() lève
            // "Path is not available when using browserType.connect()").
            Path fichierTemporaire = Files.createTempFile("madearchive-drive-", ".zip");
            byte[] zip;
            try
            {
                telechargement.saveAs(fichierTemporaire);
                zip = Files.readAllBytes(fichierTemporaire);
            }
            finally
            {
                Files.deleteIfExists(fichierTemporaire);
            }

            List<WebImportService.FichierDistant> fichiers = dezipper(zip);
            log.info("[HeadlessImport] Dossier {} → {} fichier(s) exploitable(s)", uri, fichiers.size());
            return fichiers;
        }
        catch (BusinessException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.warn("[HeadlessImport] Échec de la récupération du dossier {} : {}", uri, e.getMessage());
            throw new BusinessException(
                "Impossible de récupérer ce dossier — vérifiez qu'il est bien partagé "
                + "\"à toute personne disposant du lien\" et qu'il n'est pas vide.");
        }
        finally
        {
            permis.release();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. Repli générique — page rendue (JS exécuté), pour re-scraping
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

    private List<WebImportService.FichierDistant> dezipper(byte[] zip) throws Exception
    {
        List<WebImportService.FichierDistant> resultat = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip)))
        {
            ZipEntry entree;
            while ((entree = zis.getNextEntry()) != null)
            {
                if (entree.isDirectory()) continue;
                if (resultat.size() >= WebImportService.MAX_LIENS_DECOUVERTS) break;

                String nom = nomDeBase(entree.getName());
                String extension = extension(nom);
                if (extension == null || !WebImportService.EXTENSIONS_SUPPORTEES.contains(extension)) continue;

                byte[] contenu = zis.readAllBytes();
                if (contenu.length == 0 || contenu.length > WebImportService.TAILLE_MAX_FICHIER) continue;

                resultat.add(new WebImportService.FichierDistant(nom, contenu));
            }
        }

        return resultat;
    }

    private String nomDeBase(String cheminZip)
    {
        String chemin = cheminZip.replace('\\', '/');
        int idx = chemin.lastIndexOf('/');
        return idx >= 0 ? chemin.substring(idx + 1) : chemin;
    }

    private String extension(String nom)
    {
        int idx = nom.lastIndexOf('.');
        if (idx < 0 || idx == nom.length() - 1) return null;
        return nom.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

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
        return browser.newContext(new Browser.NewContextOptions()
            .setLocale("fr-FR")
            .setAcceptDownloads(true));
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
