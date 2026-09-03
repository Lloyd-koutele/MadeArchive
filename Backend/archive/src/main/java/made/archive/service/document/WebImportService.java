package made.archive.service.document;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.WebImportHttpProperties;
import made.archive.dto.WebImportFileDto;
import made.archive.dto.WebImportPreviewRequestDto;
import made.archive.dto.WebImportPreviewResponseDto;
import made.archive.exception.BusinessException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Import via lien web pour le bulk upload "même type" — coller un lien direct
 * vers un fichier, ou vers une page web listant des documents (ex. une page
 * de cours avec des liens PDF/Word).
 *
 * Fonctionne en deux temps :
 *  1. {@link #previewer} — découvre les fichiers SANS les télécharger, pour
 *     affichage d'une liste de confirmation côté client.
 *  2. {@link #telecharger} — télécharge uniquement les fichiers confirmés,
 *     appelé depuis BulkUploadSameTypeService.startOcrPreviewFromWeb.
 *
 * Protection SSRF : seuls http/https sont acceptés, et toute adresse résolue
 * vers un réseau privé/interne/loopback/lien-local est refusée — avant le
 * premier appel ET avant chaque téléchargement de fichier découvert (une page
 * scrapée pourrait sinon pointer vers une adresse interne). Ceci reste une
 * protection best-effort (une résolution DNS distincte a lieu au moment de la
 * connexion réelle) — acceptable ici car la fonctionnalité est réservée aux
 * éditeurs authentifiés (ROLE_EDITOR), pas exposée publiquement.
 *
 * Trois cas particuliers reconnus par motif d'URL, traités différemment du
 * lien générique, avant tout appel réseau — voir {@link #previewer} :
 *  - Google Docs/Slides/Sheets natif  → réécriture vers l'URL d'export officielle
 *    (aucun navigateur nécessaire, simple règle d'URL — voir docs Google).
 *  - Fichier Google Drive unique      → réécriture vers l'URL de téléchargement direct.
 *  - Dossier Google Drive             → aucune réécriture possible (Google n'expose
 *    aucune URL d'export pour un dossier entier) ; délégué à
 *    {@link HeadlessBrowserImportService#telechargerDossierDrive}, qui ouvre
 *    réellement la page et récupère le ZIP généré par le bouton "Tout télécharger".
 *
 * Pour tout autre lien HTML dont le scraping statique (Jsoup) ne trouve aucun
 * document, un dernier repli tente un rendu via navigateur headless avant
 * d'abandonner — couvre le cas générique d'une page dont le contenu dépend du
 * JavaScript, pas seulement Google Drive.
 *
 * Chaque téléchargement HTTP est bufferisé entièrement en mémoire (jusqu'à
 * 50 Mo/fichier) — {@link WebImportHttpProperties} borne le nombre de
 * téléchargements simultanés (voir {@link #executer}) pour qu'un afflux de
 * demandes ne consomme pas plusieurs Go de tas JVM et ne dégrade pas le reste
 * de l'application, qui partage le même processus (même principe que
 * WebImportHeadlessProperties côté navigateur headless).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebImportService
{
    /** Un fichier téléchargé, prêt pour l'OCR — nom d'origine + contenu brut. */
    public record FichierDistant(String nomFichier, byte[] bytes) {}

    static final Set<String> EXTENSIONS_SUPPORTEES = Set.of(
        "pdf", "doc", "docx", "odt", "rtf", "txt",
        "xls", "xlsx", "ods", "csv",
        "jpg", "jpeg", "png", "tif", "tiff", "bmp"
    );

    /** Content-Type → extension, pour les fichiers directs sans extension reconnaissable dans l'URL. */
    private static final Map<String, String> CONTENT_TYPE_VERS_EXTENSION = Map.ofEntries(
        Map.entry("application/pdf", "pdf"),
        Map.entry("application/msword", "doc"),
        Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
        Map.entry("application/vnd.ms-excel", "xls"),
        Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        Map.entry("application/vnd.oasis.opendocument.text", "odt"),
        Map.entry("application/vnd.oasis.opendocument.spreadsheet", "ods"),
        Map.entry("application/rtf", "rtf"),
        Map.entry("text/csv", "csv"),
        Map.entry("text/plain", "txt"),
        Map.entry("image/jpeg", "jpg"),
        Map.entry("image/png", "png"),
        Map.entry("image/tiff", "tiff"),
        Map.entry("image/bmp", "bmp")
    );

    private static final Duration TIMEOUT               = Duration.ofSeconds(15);
    private static final long     TAILLE_MAX_PAGE_OCTETS = 5L * 1024 * 1024;   // 5 Mo — page HTML à scraper
    static final long             TAILLE_MAX_FICHIER     = 50L * 1024 * 1024;  // 50 Mo — par fichier téléchargé
    static final int              MAX_LIENS_DECOUVERTS   = 50;

    /** cache://{sessionId}/{index} — identifiant synthétique des fichiers d'un dossier déjà téléchargé (voir WebImportFolderCache). */
    private static final String PREFIXE_CACHE = "cache://";

    private static final Pattern PATTERN_DRIVE_DOSSIER = Pattern.compile(
        "drive\\.google\\.com/drive/(?:u/\\d+/)?folders/([a-zA-Z0-9_-]+)");
    private static final Pattern PATTERN_DRIVE_FICHIER = Pattern.compile(
        "drive\\.google\\.com/file/d/([a-zA-Z0-9_-]+)");
    private static final Pattern PATTERN_DOCS_DOCUMENT = Pattern.compile(
        "docs\\.google\\.com/document/d/([a-zA-Z0-9_-]+)");
    private static final Pattern PATTERN_DOCS_PRESENTATION = Pattern.compile(
        "docs\\.google\\.com/presentation/d/([a-zA-Z0-9_-]+)");
    private static final Pattern PATTERN_DOCS_SPREADSHEET = Pattern.compile(
        "docs\\.google\\.com/spreadsheets/d/([a-zA-Z0-9_-]+)");

    // Content-Disposition : "filename*=UTF-8''..." (RFC 5987, priorité — gère
    // les caractères non-ASCII) et la forme simple "filename=..." en repli.
    private static final Pattern PATTERN_DISPOSITION_FILENAME_ETOILE = Pattern.compile(
        "filename\\*=UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_DISPOSITION_FILENAME = Pattern.compile(
        "filename=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    // Signatures binaires (nombre magique) — dernier recours pour nommer un
    // fichier quand ni l'URL, ni Content-Disposition, ni Content-Type ne
    // donnent d'indice exploitable (voir detecterExtensionParSignature).
    private static final byte[] SIGNATURE_PDF      = {'%', 'P', 'D', 'F'};
    private static final byte[] SIGNATURE_JPEG     = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] SIGNATURE_PNG      = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] SIGNATURE_BMP      = {'B', 'M'};
    private static final byte[] SIGNATURE_TIFF_LE  = {'I', 'I', 0x2A, 0x00}; // little-endian
    private static final byte[] SIGNATURE_TIFF_BE  = {'M', 'M', 0x00, 0x2A}; // big-endian

    private final HeadlessBrowserImportService headlessBrowserImportService;
    private final WebImportFolderCache         folderCache;
    private final WebImportHttpProperties      proprietesHttp;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER) // gérés à la main, avec re-validation SSRF à chaque saut
        .build();

    private Semaphore permis;

    @PostConstruct
    private void initialiserPermis()
    {
        permis = new Semaphore(Math.max(1, proprietesHttp.getMaxConcurrent()));
    }

    // ═══════════════════════════════════════════════════════════════
    // Découverte (aperçu, sans téléchargement)
    // ═══════════════════════════════════════════════════════════════

    public WebImportPreviewResponseDto previewer(WebImportPreviewRequestDto requete)
    {
        if (!StringUtils.hasText(requete.getUrl()))
        {
            throw new BusinessException("Le lien est obligatoire");
        }

        URI uri = resoudreEtValider(requete.getUrl());

        // Dossier Google Drive : aucune réécriture d'URL possible pour "tout le
        // dossier" — délégué directement au navigateur headless, avant tout
        // appel HTTP classique.
        if (estDossierDrive(uri.toString()))
        {
            return previewerDossierDrive(uri);
        }

        // Google Docs/Slides/Sheets natif ou fichier Drive unique : simple
        // réécriture vers l'URL d'export/téléchargement direct de Google — le
        // reste du traitement (HTTP classique ci-dessous) est inchangé.
        uri = reecrireLienGoogleSiApplicable(uri);

        HttpResponse<byte[]> reponse = executer(uri, TAILLE_MAX_PAGE_OCTETS);
        String contentType = enTeteContentType(reponse);

        // Cas particulier connu : pour un gros fichier (~25 Mo+), Drive renvoie
        // une page d'avertissement HTML ("impossible d'analyser ce fichier avec
        // un antivirus") au lieu du fichier lui-même sur l'URL de téléchargement
        // direct — sans ce garde-fou, cette page serait scrapée ou traitée comme
        // le document, ce qui n'a pas de sens ici (le comportement générique de
        // repli ci-dessous ne le résoudrait pas non plus).
        if (uri.toString().contains("drive.google.com/uc?export=download") && estTypeHtml(contentType))
        {
            throw new BusinessException(
                "Ce fichier Google Drive est trop volumineux pour un téléchargement direct "
                + "(Drive affiche une page d'avertissement à la place) — téléchargez-le "
                + "manuellement puis déposez-le, ou réduisez sa taille.");
        }

        if (estTypeHtml(contentType))
        {
            List<WebImportFileDto> fichiers = extraireLiensDocuments(
                new String(reponse.body(), StandardCharsets.UTF_8), uri.toString());

            if (fichiers.isEmpty())
            {
                // Repli : la page ne montre peut-être ses liens qu'après exécution
                // du JavaScript (comme Drive) — pas seulement un cas Google, valable
                // pour n'importe quel fournisseur. Best-effort : on abandonne
                // silencieusement en cas d'échec du navigateur headless lui-même,
                // l'erreur "aucun document trouvé" ci-dessous couvrira ce cas.
                String htmlRendu = tenterViaNavigateurHeadless(uri);
                if (htmlRendu != null)
                {
                    fichiers = extraireLiensDocuments(htmlRendu, uri.toString());
                }
            }

            if (fichiers.isEmpty())
            {
                throw new BusinessException(
                    "Aucun document trouvé sur cette page (extensions prises en charge : "
                    + String.join(", ", EXTENSIONS_SUPPORTEES) + ")");
            }

            return WebImportPreviewResponseDto.builder()
                .sourceUrl(uri.toString())
                .type("PAGE_WEB")
                .fichiers(fichiers)
                .build();
        }

        // Sinon : on considère que le lien pointe directement sur un fichier.
        String nomFichier = nommerFichierDirect(uri, reponse);
        return WebImportPreviewResponseDto.builder()
            .sourceUrl(uri.toString())
            .type("FICHIER_DIRECT")
            .fichiers(List.of(WebImportFileDto.builder().nomFichier(nomFichier).url(uri.toString()).build()))
            .build();
    }

    /**
     * Dossier Google Drive public : téléchargé intégralement dès l'aperçu (pas
     * d'URL par fichier stable à re-télécharger plus tard, contrairement aux
     * autres types) — les octets sont mis en cache (WebImportFolderCache) en
     * attendant la confirmation utilisateur ; {@link #telecharger} les relit
     * depuis ce cache via l'identifiant synthétique "cache://{session}/{index}".
     */
    private WebImportPreviewResponseDto previewerDossierDrive(URI uri)
    {
        List<FichierDistant> fichiers = headlessBrowserImportService.telechargerDossierDrive(uri);

        if (fichiers.isEmpty())
        {
            throw new BusinessException(
                "Aucun document exploitable trouvé dans ce dossier (extensions prises en charge : "
                + String.join(", ", EXTENSIONS_SUPPORTEES) + ")");
        }

        UUID sessionId = folderCache.storer(fichiers);

        List<WebImportFileDto> dtos = new ArrayList<>();
        for (int i = 0; i < fichiers.size(); i++)
        {
            dtos.add(WebImportFileDto.builder()
                .nomFichier(fichiers.get(i).nomFichier())
                .url(PREFIXE_CACHE + sessionId + "/" + i)
                .build());
        }

        return WebImportPreviewResponseDto.builder()
            .sourceUrl(uri.toString())
            .type("DOSSIER")
            .fichiers(dtos)
            .build();
    }

    /** Best-effort : ne lève jamais — retourne null si le navigateur headless échoue ou est indisponible. */
    private String tenterViaNavigateurHeadless(URI uri)
    {
        try
        {
            return headlessBrowserImportService.rendreEtRecupererHtml(uri);
        }
        catch (Exception e)
        {
            log.warn("[WebImport] Repli navigateur headless échoué pour {} : {}", uri, e.getMessage());
            return null;
        }
    }

    /**
     * Réécrit un lien Google Docs/Slides/Sheets natif vers son URL d'export
     * officielle, ou un lien de fichier Drive unique vers son URL de
     * téléchargement direct — convention documentée par Google, pas du
     * scraping. Retourne l'URI inchangée si aucun motif ne correspond.
     */
    /** Vrai si l'URL pointe sur un DOSSIER Google Drive complet (délégué au
     *  navigateur headless — voir previewer()). Visibilité package-private
     *  délibérée : détail d'implémentation, mais testable directement plutôt
     *  que seulement via previewer() (qui ferait un vrai appel réseau). */
    boolean estDossierDrive(String url)
    {
        return PATTERN_DRIVE_DOSSIER.matcher(url).find();
    }

    /** Package-private pour la même raison qu'estDossierDrive ci-dessus — les
     *  trois bugs réels documentés en 5.3.2/5.12 sont tous nés dans des
     *  motifs proches de celui-ci, d'où l'intérêt de le tester isolément. */
    URI reecrireLienGoogleSiApplicable(URI uri)
    {
        String url = uri.toString();

        Matcher m = PATTERN_DOCS_DOCUMENT.matcher(url);
        if (m.find()) return URI.create("https://docs.google.com/document/d/" + m.group(1) + "/export?format=pdf");

        m = PATTERN_DOCS_PRESENTATION.matcher(url);
        if (m.find()) return URI.create("https://docs.google.com/presentation/d/" + m.group(1) + "/export/pdf");

        m = PATTERN_DOCS_SPREADSHEET.matcher(url);
        if (m.find()) return URI.create("https://docs.google.com/spreadsheets/d/" + m.group(1) + "/export?format=pdf");

        m = PATTERN_DRIVE_FICHIER.matcher(url);
        if (m.find()) return URI.create("https://drive.google.com/uc?export=download&id=" + m.group(1));

        return uri;
    }

    // ═══════════════════════════════════════════════════════════════
    // Téléchargement des fichiers confirmés
    // ═══════════════════════════════════════════════════════════════

    public List<FichierDistant> telecharger(List<String> urls)
    {
        if (urls == null || urls.isEmpty())
        {
            throw new BusinessException("Aucun fichier confirmé à importer");
        }

        List<FichierDistant> resultats = new ArrayList<>();

        for (String url : urls)
        {
            try
            {
                // Fichier d'un dossier déjà téléchargé à l'aperçu (voir
                // previewerDossierDrive) — pas de nouvelle requête HTTP, juste
                // une relecture du cache.
                if (url.startsWith(PREFIXE_CACHE))
                {
                    FichierDistant fichier = resoudreDepuisCache(url);
                    if (fichier == null)
                    {
                        log.warn("[WebImport] Session dossier expirée ou introuvable : {}", url);
                        continue;
                    }
                    resultats.add(fichier);
                    continue;
                }

                URI uri = resoudreEtValider(url);
                HttpResponse<byte[]> reponse = executer(uri, TAILLE_MAX_FICHIER);

                byte[] contenu = reponse.body();
                if (contenu.length > TAILLE_MAX_FICHIER)
                {
                    log.warn("[WebImport] Ignoré (trop volumineux : {} octets) : {}", contenu.length, url);
                    continue;
                }

                resultats.add(new FichierDistant(
                    nommerFichierDirect(uri, reponse), contenu));
                log.info("[WebImport] Téléchargé : {} ({} octets)", url, contenu.length);
            }
            catch (BusinessException e)
            {
                log.warn("[WebImport] Ignoré ({}) : {}", e.getMessage(), url);
            }
            catch (Exception e)
            {
                log.warn("[WebImport] Échec du téléchargement de {} : {}", url, e.getMessage());
            }
        }

        if (resultats.isEmpty())
        {
            throw new BusinessException("Aucun des fichiers confirmés n'a pu être téléchargé");
        }

        return resultats;
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers privés
    // ═══════════════════════════════════════════════════════════════

    /** Parse "cache://{sessionId}/{index}" et relit le fichier correspondant dans WebImportFolderCache. */
    private FichierDistant resoudreDepuisCache(String url)
    {
        try
        {
            String reste = url.substring(PREFIXE_CACHE.length()); // "{sessionId}/{index}"
            int sep = reste.lastIndexOf('/');
            UUID sessionId = UUID.fromString(reste.substring(0, sep));
            int index = Integer.parseInt(reste.substring(sep + 1));
            return folderCache.recuperer(sessionId, index);
        }
        catch (Exception e)
        {
            log.warn("[WebImport] Identifiant de cache invalide : {}", url);
            return null;
        }
    }

    /** Résout l'URL, valide le schéma et l'adresse (protection SSRF), et suit manuellement les redirections. */
    private URI resoudreEtValider(String url)
    {
        URI uri;
        try
        {
            uri = URI.create(url.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw new BusinessException("Lien invalide : " + url);
        }

        String schema = uri.getScheme();
        if (schema == null || !(schema.equalsIgnoreCase("http") || schema.equalsIgnoreCase("https")))
        {
            throw new BusinessException("Seuls les liens http:// ou https:// sont pris en charge");
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host))
        {
            throw new BusinessException("Lien invalide — hôte manquant");
        }

        InetAddress[] adresses;
        try
        {
            adresses = InetAddress.getAllByName(host);
        }
        catch (UnknownHostException e)
        {
            throw new BusinessException("Impossible de résoudre l'adresse : " + host);
        }

        for (InetAddress adresse : adresses)
        {
            if (adresse.isAnyLocalAddress() || adresse.isLoopbackAddress()
                || adresse.isLinkLocalAddress() || adresse.isSiteLocalAddress()
                || adresse.isMulticastAddress() || estAdresseIpv6UniqueLocale(adresse))
            {
                throw new BusinessException(
                    "Cette adresse pointe vers un réseau interne/privé — import refusé : " + host);
            }
        }

        return uri;
    }

    /** fc00::/7 (IPv6 unique local) — non couvert par InetAddress.isSiteLocalAddress(). */
    private boolean estAdresseIpv6UniqueLocale(InetAddress adresse)
    {
        byte[] octets = adresse.getAddress();
        return octets.length == 16 && (octets[0] & 0xfe) == 0xfc;
    }

    /**
     * Une seule acquisition de permis par appel — les sauts de redirection
     * internes à cette méthode font partie de la même opération logique
     * ("récupérer ce lien"), pas d'acquisitions séparées.
     */
    private HttpResponse<byte[]> executer(URI uri, long tailleMaxOctets)
    {
        if (!acquerirPermis())
        {
            throw new BusinessException(
                "Trop de téléchargements en cours sur ce serveur — réessayez dans quelques instants.");
        }

        try
        {
            HttpRequest requete = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", "MadeArchive-Import/1.0")
                .GET()
                .build();

            HttpResponse<byte[]> reponse = client.send(requete, HttpResponse.BodyHandlers.ofByteArray());

            // Redirection suivie manuellement (max 3 sauts), avec re-validation SSRF de la cible.
            int sauts = 0;
            while (reponse.statusCode() >= 300 && reponse.statusCode() < 400 && sauts < 3)
            {
                String location = reponse.headers().firstValue("Location")
                    .orElseThrow(() -> new BusinessException("Redirection sans destination"));
                URI cible = uri.resolve(location);
                URI cibleValidee = resoudreEtValider(cible.toString());

                HttpRequest requeteSuivante = HttpRequest.newBuilder(cibleValidee)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "MadeArchive-Import/1.0")
                    .GET()
                    .build();
                reponse = client.send(requeteSuivante, HttpResponse.BodyHandlers.ofByteArray());
                uri = cibleValidee;
                sauts++;
            }

            if (reponse.statusCode() < 200 || reponse.statusCode() >= 300)
            {
                throw new BusinessException("Le serveur distant a répondu avec le code " + reponse.statusCode());
            }

            if (reponse.body().length > tailleMaxOctets)
            {
                throw new BusinessException("Contenu trop volumineux à cette adresse");
            }

            return reponse;
        }
        catch (BusinessException e)
        {
            throw e;
        }
        catch (IOException | InterruptedException e)
        {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BusinessException("Impossible de joindre cette adresse : " + e.getMessage());
        }
        finally
        {
            permis.release();
        }
    }

    /** Attend un "slot" libre (max proprietesHttp.maxConcurrent en même temps) jusqu'à attenteSlotSecondes, sinon refuse. */
    private boolean acquerirPermis()
    {
        try
        {
            return permis.tryAcquire(proprietesHttp.getAttenteSlotSecondes(), TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String enTeteContentType(HttpResponse<byte[]> reponse)
    {
        return reponse.headers().firstValue("Content-Type")
            .map(v -> v.split(";")[0].trim().toLowerCase(Locale.ROOT))
            .orElse("");
    }

    private boolean estTypeHtml(String contentType)
    {
        return contentType.contains("text/html") || contentType.contains("application/xhtml");
    }

    private List<WebImportFileDto> extraireLiensDocuments(String html, String baseUri)
    {
        Document doc = Jsoup.parse(html, baseUri);
        // LinkedHashMap : dédoublonne par URL absolue tout en conservant l'ordre d'apparition sur la page.
        Map<String, String> trouves = new LinkedHashMap<>();

        for (Element lien : doc.select("a[href]"))
        {
            String href = lien.attr("abs:href");
            if (!StringUtils.hasText(href)) continue;

            String extension = extraireExtension(href);
            if (extension == null || !EXTENSIONS_SUPPORTEES.contains(extension)) continue;

            if (trouves.size() >= MAX_LIENS_DECOUVERTS) break;
            trouves.putIfAbsent(href, nommerDepuisChemin(href, extension));
        }

        List<WebImportFileDto> resultat = new ArrayList<>();
        trouves.forEach((url, nom) -> resultat.add(WebImportFileDto.builder().nomFichier(nom).url(url).build()));
        return resultat;
    }

    private String extraireExtension(String url)
    {
        try
        {
            String path = URI.create(url).getPath();
            if (path == null) return null;
            int idx = path.lastIndexOf('.');
            if (idx < 0 || idx == path.length() - 1) return null;
            return path.substring(idx + 1).toLowerCase(Locale.ROOT);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Détermine le nom (et donc l'extension) d'un fichier téléchargé
     * directement, dans l'ordre de fiabilité décroissante :
     *  1. Extension déjà présente dans le chemin de l'URL.
     *  2. Nom réel indiqué par l'en-tête Content-Disposition — souvent le SEUL
     *     indice fiable quand le Content-Type est générique (constaté sur
     *     Google Drive : son URL de téléchargement direct répond
     *     "application/octet-stream" quel que soit le fichier, alors que
     *     Content-Disposition porte bien son vrai nom).
     *  3. Extension déduite d'un Content-Type reconnu.
     *  4. Signature des premiers octets du contenu (PDF/JPEG/PNG/BMP/TIFF) —
     *     dernier recours quand ni l'URL, ni Content-Disposition, ni
     *     Content-Type ne donnent d'indice exploitable.
     */
    private String nommerFichierDirect(URI uri, HttpResponse<byte[]> reponse)
    {
        String extensionExistante = extraireExtension(uri.toString());
        if (extensionExistante != null && EXTENSIONS_SUPPORTEES.contains(extensionExistante))
        {
            return nommerDepuisChemin(uri.toString(), extensionExistante);
        }

        String nomDisposition = nomDepuisContentDisposition(reponse);
        if (nomDisposition != null)
        {
            String extensionDisposition = extraireExtensionSimple(nomDisposition);
            if (extensionDisposition != null && EXTENSIONS_SUPPORTEES.contains(extensionDisposition))
            {
                return nomDisposition;
            }
        }

        String contentType = enTeteContentType(reponse);
        String extensionDepuisType = CONTENT_TYPE_VERS_EXTENSION.get(contentType);
        if (extensionDepuisType != null)
        {
            return "document." + extensionDepuisType;
        }

        String extensionSignature = detecterExtensionParSignature(reponse.body());
        if (extensionSignature != null)
        {
            String base = nomDisposition != null ? sansExtension(nomDisposition) : "document";
            return base + "." + extensionSignature;
        }

        throw new BusinessException(
            "Type de contenu non pris en charge à cette adresse (" + (contentType.isBlank() ? "inconnu" : contentType) + ")");
    }

    /**
     * Extrait le nom de fichier de l'en-tête Content-Disposition, en
     * préférant la forme RFC 5987 ("filename*=UTF-8''...", gère les
     * caractères non-ASCII) à la forme simple ("filename=...") si les deux
     * sont présentes.
     */
    private String nomDepuisContentDisposition(HttpResponse<byte[]> reponse)
    {
        String valeur = reponse.headers().firstValue("Content-Disposition").orElse(null);
        if (valeur == null) return null;

        Matcher mEtoile = PATTERN_DISPOSITION_FILENAME_ETOILE.matcher(valeur);
        if (mEtoile.find())
        {
            try
            {
                String decode = URLDecoder.decode(mEtoile.group(1).trim(), StandardCharsets.UTF_8);
                if (StringUtils.hasText(decode)) return decode;
            }
            catch (Exception ignore) {}
        }

        Matcher m = PATTERN_DISPOSITION_FILENAME.matcher(valeur);
        if (m.find())
        {
            String nom = m.group(1).trim();
            if (StringUtils.hasText(nom)) return nom;
        }

        return null;
    }

    private String extraireExtensionSimple(String nomFichier)
    {
        int idx = nomFichier.lastIndexOf('.');
        if (idx < 0 || idx == nomFichier.length() - 1) return null;
        return nomFichier.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String sansExtension(String nomFichier)
    {
        int idx = nomFichier.lastIndexOf('.');
        return idx > 0 ? nomFichier.substring(0, idx) : nomFichier;
    }

    /** Signatures binaires reconnues — uniquement les formats non-ambigus (les formats zip-based : docx/xlsx/pptx/odt/ods, partagent tous la même signature "PK" et ne peuvent pas être distingués ainsi). */
    private String detecterExtensionParSignature(byte[] contenu)
    {
        if (demarrePar(contenu, SIGNATURE_PDF))  return "pdf";
        if (demarrePar(contenu, SIGNATURE_JPEG)) return "jpg";
        if (demarrePar(contenu, SIGNATURE_PNG))  return "png";
        if (demarrePar(contenu, SIGNATURE_BMP))  return "bmp";
        if (demarrePar(contenu, SIGNATURE_TIFF_LE) || demarrePar(contenu, SIGNATURE_TIFF_BE)) return "tiff";
        return null;
    }

    private boolean demarrePar(byte[] contenu, byte[] signature)
    {
        if (contenu.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++)
        {
            if (contenu[i] != signature[i]) return false;
        }
        return true;
    }

    private String nommerDepuisChemin(String url, String extensionAttendue)
    {
        try
        {
            String path = URI.create(url).getPath();
            String segment = path != null && path.contains("/")
                ? path.substring(path.lastIndexOf('/') + 1) : path;
            String decode = StringUtils.hasText(segment)
                ? URLDecoder.decode(segment, StandardCharsets.UTF_8) : "";
            return StringUtils.hasText(decode) ? decode : "document." + extensionAttendue;
        }
        catch (Exception e)
        {
            return "document." + extensionAttendue;
        }
    }
}
