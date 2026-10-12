package made.archive.service.importweb;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.GoogleDriveProperties;
import made.archive.dto.WebImportFileDto;
import made.archive.exception.BusinessException;

/**
 * Import d'un dossier Google Drive PAR L'API OFFICIELLE : on liste les fichiers du dossier (et de ses sous-dossiers,
 * jusqu'à une profondeur bornée), puis chaque fichier est téléchargé SÉPARÉMENT par WebImportService. Aucune archive
 * n'est jamais téléchargée ni décompressée.
 *
 * La clé d'API ne quitte jamais le serveur : les adresses renvoyées au navigateur n'en portent pas, elle n'est
 * ajoutée qu'au moment du téléchargement ({@link #avecCle}), et SEULEMENT pour une adresse de l'API Drive
 * ({@link #estUrlApiDrive}) — jamais pour un autre hôte, quel que soit le lien collé.
 *
 * Les fichiers Google natifs (Docs, Sheets, Slides) sont exportés en PDF ; les autres types Google (formulaires,
 * dessins, raccourcis) sont ignorés, comme les fichiers d'un format non pris en charge ou trop volumineux.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleDriveService
{
    /** Résultat d'un listage : fichiers retenus, nombre d'éléments écartés, liste coupée par une limite. */
    public record Listage(List<WebImportFileDto> fichiers, int ignores, boolean tronque) {}

    private static final Pattern PATTERN_DOSSIER = Pattern.compile(
        "drive\\.google\\.com/drive/(?:u/\\d+/)?folders/([a-zA-Z0-9_-]+)");
    private static final Pattern IDENTIFIANT = Pattern.compile("[a-zA-Z0-9_-]+");
    private static final Pattern CHEMIN_API = Pattern.compile("^/drive/v3/files/[a-zA-Z0-9_-]+(/export)?$");

    private static final String TYPE_DOSSIER = "application/vnd.google-apps.folder";
    private static final String TYPE_DOCUMENT = "application/vnd.google-apps.document";
    private static final String TYPE_TABLEUR = "application/vnd.google-apps.spreadsheet";
    private static final String TYPE_PRESENTATION = "application/vnd.google-apps.presentation";

    /** Une page de résultats de l'API est un petit JSON : 2 Mo au plus, sinon quelque chose cloche. */
    private static final long TAILLE_MAX_REPONSE_API = 2L * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GoogleDriveProperties proprietes;
    private final ClientHttpSecurise clientHttp;

    public boolean estActive()
    {
        return proprietes.active();
    }

    /** Identifiant du dossier si ce lien en désigne un, sinon vide. */
    public Optional<String> extraireIdDossier(String url)
    {
        Matcher m = PATTERN_DOSSIER.matcher(url == null ? "" : url);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────
    // Listage
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * @param extensionsSupportees extensions de fichier acceptées par l'import
     * @param tailleMaxFichier     taille maximale d'un fichier, en octets — au-delà il est ignoré
     */
    public Listage lister(String dossierId, Set<String> extensionsSupportees, long tailleMaxFichier)
    {
        if (!estActive())
        {
            throw new BusinessException("L'import d'un dossier Google Drive n'est pas activé sur ce serveur.");
        }
        if (dossierId == null || !IDENTIFIANT.matcher(dossierId).matches())
        {
            throw new BusinessException("Identifiant de dossier Google Drive invalide");
        }

        List<WebImportFileDto> fichiers = new ArrayList<>();
        int ignores = 0;
        boolean tronque = false;
        int appels = 0;

        record AParcourir(String id, int profondeur) {}
        Deque<AParcourir> file = new ArrayDeque<>();
        file.add(new AParcourir(dossierId, 0));

        parcours:
        while (!file.isEmpty())
        {
            AParcourir courant = file.poll();
            String pageSuivante = null;
            do
            {
                if (appels >= proprietes.getMaxAppels())
                {
                    tronque = true;
                    break parcours;
                }
                appels++;
                JsonNode page = appelerListe(courant.id(), pageSuivante, appels == 1);

                for (JsonNode element : page.path("files"))
                {
                    String id = element.path("id").asText("");
                    String nom = element.path("name").asText("");
                    String type = element.path("mimeType").asText("");
                    if (!IDENTIFIANT.matcher(id).matches() || nom.isBlank())
                    {
                        ignores++;
                        continue;
                    }

                    if (TYPE_DOSSIER.equals(type))
                    {
                        if (courant.profondeur() < proprietes.getMaxProfondeur())
                        {
                            file.add(new AParcourir(id, courant.profondeur() + 1));
                        }
                        else
                        {
                            ignores++;
                        }
                        continue;
                    }

                    Optional<WebImportFileDto> retenu = versFichier(id, nom, type, element.path("size").asLong(-1),
                        extensionsSupportees, tailleMaxFichier);
                    if (retenu.isEmpty())
                    {
                        ignores++;
                        continue;
                    }
                    if (fichiers.size() >= proprietes.getMaxFichiers())
                    {
                        tronque = true;
                        break parcours;
                    }
                    fichiers.add(retenu.get());
                }
                pageSuivante = page.path("nextPageToken").asText(null);
            }
            while (pageSuivante != null && !pageSuivante.isBlank());
        }

        log.info("[GoogleDrive] Dossier listé : {} fichier(s) retenu(s), {} ignoré(s), {} appel(s) API{}",
            fichiers.size(), ignores, appels, tronque ? " (liste tronquée)" : "");
        return new Listage(fichiers, ignores, tronque);
    }

    private Optional<WebImportFileDto> versFichier(String id, String nom, String type, long taille,
                                                   Set<String> extensions, long tailleMax)
    {
        boolean natif = TYPE_DOCUMENT.equals(type) || TYPE_TABLEUR.equals(type) || TYPE_PRESENTATION.equals(type);
        if (natif)
        {
            // Exporté en PDF : l'API refuse l'export au-delà de 10 Mo, ce qui apparaîtra à l'import de ce fichier.
            String nomPdf = nom.toLowerCase(Locale.ROOT).endsWith(".pdf") ? nom : nom + ".pdf";
            return Optional.of(WebImportFileDto.builder().nomFichier(nettoyerNom(nomPdf))
                .url(urlSansCle("/drive/v3/files/" + id + "/export", "mimeType=application%2Fpdf", nomPdf)).build());
        }
        if (type.startsWith("application/vnd.google-apps."))
        {
            return Optional.empty();   // formulaire, dessin, raccourci, site... : rien à archiver
        }

        int point = nom.lastIndexOf('.');
        String extension = point >= 0 ? nom.substring(point + 1).toLowerCase(Locale.ROOT) : "";
        if (!extensions.contains(extension) || (taille >= 0 && taille > tailleMax))
        {
            return Optional.empty();
        }
        return Optional.of(WebImportFileDto.builder().nomFichier(nettoyerNom(nom))
            .url(urlSansCle("/drive/v3/files/" + id, "alt=media", nom)).build());
    }

    private String urlSansCle(String chemin, String requete, String nom)
    {
        return proprietes.getApiBaseUrl() + chemin + "?" + requete + "#nom="
            + URLEncoder.encode(nom, StandardCharsets.UTF_8);
    }

    private JsonNode appelerListe(String dossierId, String pageToken, boolean premierAppel)
    {
        String q = "'" + dossierId + "' in parents and trashed=false";
        String url = proprietes.getApiBaseUrl() + "/drive/v3/files"
            + "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
            + "&fields=" + URLEncoder.encode("nextPageToken,files(id,name,mimeType,size)", StandardCharsets.UTF_8)
            + "&pageSize=100&orderBy=name&supportsAllDrives=true&includeItemsFromAllDrives=true"
            + (pageToken != null ? "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8) : "")
            + "&key=" + URLEncoder.encode(proprietes.getApiKey(), StandardCharsets.UTF_8);
        try
        {
            ClientHttpSecurise.Reponse reponse = clientHttp.telecharger(URI.create(url), TAILLE_MAX_REPONSE_API);
            return JSON.readTree(reponse.corps());
        }
        catch (ErreurHttpException e)
        {
            throw new BusinessException(messagePourCode(e.statut(), premierAppel));
        }
        catch (BusinessException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new BusinessException("Réponse inattendue de Google Drive : " + e.getMessage());
        }
    }

    private static String messagePourCode(int code, boolean premierAppel)
    {
        return switch (code)
        {
            case 404 -> "Dossier Google Drive introuvable : vérifiez le lien.";
            case 403 -> "Accès refusé par Google Drive : le dossier doit être partagé avec « Tous les utilisateurs disposant "
                + "du lien », et le quota de la clé d'API ne doit pas être épuisé.";
            case 400 -> "Google Drive a refusé la demande (clé d'API invalide ou dossier incorrect).";
            case 429 -> "Google Drive limite temporairement les demandes : réessayez dans quelques minutes.";
            default -> "Google Drive est indisponible pour le moment (code " + code + ")"
                + (premierAppel ? "." : " — la liste n'a pas pu être terminée.");
        };
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────
    // Téléchargement : adresses de l'API, clé ajoutée côté serveur seulement
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Vrai si cette adresse est un téléchargement ou un export de l'API Drive (et pas un autre hôte ou chemin). */
    public boolean estUrlApiDrive(URI uri)
    {
        URI base = URI.create(proprietes.getApiBaseUrl());
        return uri != null
            && base.getScheme().equalsIgnoreCase(String.valueOf(uri.getScheme()))
            && base.getHost().equalsIgnoreCase(String.valueOf(uri.getHost()))
            && base.getPort() == uri.getPort()
            && uri.getPath() != null && CHEMIN_API.matcher(uri.getPath()).matches();
    }

    /** Nom du fichier tel que fixé à l'aperçu (fragment « #nom=… », jamais envoyé au serveur distant). */
    public Optional<String> nomImpose(URI uri)
    {
        String fragment = uri.getRawFragment();
        if (fragment == null || !fragment.startsWith("nom="))
        {
            return Optional.empty();
        }
        String nom = nettoyerNom(java.net.URLDecoder.decode(fragment.substring(4), StandardCharsets.UTF_8));
        return nom.isBlank() ? Optional.empty() : Optional.of(nom);
    }

    /** Adresse prête à l'appel : sans fragment, avec la clé d'API. Refuse toute adresse qui n'est pas celle de l'API Drive. */
    public URI avecCle(URI uri)
    {
        if (!estActive())
        {
            throw new BusinessException("L'import depuis Google Drive n'est pas activé sur ce serveur.");
        }
        if (!estUrlApiDrive(uri))
        {
            throw new IllegalArgumentException("Adresse hors de l'API Google Drive");
        }
        String requete = uri.getRawQuery() != null ? uri.getRawQuery() + "&" : "";
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath() + "?" + requete
            + "key=" + URLEncoder.encode(proprietes.getApiKey(), StandardCharsets.UTF_8));
    }

    /** Un nom de fichier ne contient ni séparateur de chemin ni caractère de contrôle, et reste de longueur raisonnable. */
    static String nettoyerNom(String nom)
    {
        String net = nom.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        return net.length() > 200 ? net.substring(net.length() - 200) : net;
    }
}
