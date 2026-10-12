package made.archive.service.importweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import made.archive.config.GoogleDriveProperties;
import made.archive.dto.WebImportFileDto;
import made.archive.exception.BusinessException;

/** Faux serveur local de l'API Drive : le listage, les filtres, les limites et la protection de la clé d'API. */
@Tag("unit")
class GoogleDriveServiceTest
{
    private HttpServer serveur;
    private GoogleDriveProperties proprietes;
    private GoogleDriveService service;
    private final List<String> requetes = new CopyOnWriteArrayList<>();
    private int statut = 200;

    /** Réponse JSON par identifiant de dossier parent. */
    private final java.util.Map<String, String> reponses = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Set<String> EXTENSIONS = Set.of("pdf", "docx", "png");

    @BeforeEach
    void demarrer() throws IOException
    {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.createContext("/", ex ->
        {
            String requete = ex.getRequestURI().getRawQuery();
            requetes.add(requete);
            String q = java.net.URLDecoder.decode(requete.replaceAll(".*q=([^&]*).*", "$1"), StandardCharsets.UTF_8);
            String dossier = q.replaceAll("^'([^']*)'.*", "$1");
            byte[] corps = reponses.getOrDefault(dossier, "{\"files\":[]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(statut, corps.length);
            ex.getResponseBody().write(corps);
            ex.close();
        });
        serveur.start();

        proprietes = new GoogleDriveProperties();
        proprietes.setApiKey("CLE-SECRETE-DE-TEST");
        proprietes.setApiBaseUrl("http://127.0.0.1:" + serveur.getAddress().getPort());
        service = new GoogleDriveService(proprietes, new ClientHttpSecurise(a -> false));
    }

    @AfterEach
    void arreter()
    {
        serveur.stop(0);
    }

    private static String fichier(String id, String nom, String type, Long taille)
    {
        return "{\"id\":\"" + id + "\",\"name\":\"" + nom + "\",\"mimeType\":\"" + type + "\""
            + (taille != null ? ",\"size\":\"" + taille + "\"" : "") + "}";
    }

    private static String liste(String... elements)
    {
        return "{\"files\":[" + String.join(",", elements) + "]}";
    }

    @Test
    void listeLesFichiersPrisEnCharge_etEcarteLeReste()
    {
        reponses.put("racine", liste(
            fichier("a1", "releve.pdf", "application/pdf", 1000L),
            fichier("a2", "photo.PNG", "image/png", 2000L),
            fichier("a3", "archive.zip", "application/zip", 500L),         // format non pris en charge
            fichier("a4", "gros.pdf", "application/pdf", 80L * 1024 * 1024), // trop volumineux
            fichier("a5", "formulaire", "application/vnd.google-apps.form", null),
            fichier("a6", "Cours", "application/vnd.google-apps.document", null)));

        GoogleDriveService.Listage l = service.lister("racine", EXTENSIONS, 50L * 1024 * 1024);

        assertThat(l.fichiers()).extracting(WebImportFileDto::getNomFichier)
            .containsExactly("releve.pdf", "photo.PNG", "Cours.pdf");
        assertThat(l.ignores()).isEqualTo(3);
        assertThat(l.tronque()).isFalse();
    }

    @Test
    void lesAdressesRenvoyeesAuNavigateurNePortentJamaisLaCle()
    {
        reponses.put("racine", liste(fichier("a1", "releve.pdf", "application/pdf", 10L),
            fichier("a2", "Cours", "application/vnd.google-apps.document", null)));

        GoogleDriveService.Listage l = service.lister("racine", EXTENSIONS, 1_000_000L);

        assertThat(l.fichiers()).allSatisfy(f -> assertThat(f.getUrl()).doesNotContain("CLE-SECRETE").doesNotContain("key="));
        assertThat(l.fichiers().get(0).getUrl()).contains("/drive/v3/files/a1").contains("alt=media");
        assertThat(l.fichiers().get(1).getUrl()).contains("/drive/v3/files/a2/export").contains("application%2Fpdf");
    }

    @Test
    void descendDansLesSousDossiers_jusquaLaProfondeurMaximale()
    {
        proprietes.setMaxProfondeur(1);
        reponses.put("racine", liste(fichier("d1", "Sous", "application/vnd.google-apps.folder", null),
            fichier("f0", "a.pdf", "application/pdf", 1L)));
        reponses.put("d1", liste(fichier("d2", "Profond", "application/vnd.google-apps.folder", null),
            fichier("f1", "b.pdf", "application/pdf", 1L)));
        reponses.put("d2", liste(fichier("f2", "c.pdf", "application/pdf", 1L)));

        GoogleDriveService.Listage l = service.lister("racine", EXTENSIONS, 1_000_000L);

        assertThat(l.fichiers()).extracting(WebImportFileDto::getNomFichier).containsExactlyInAnyOrder("a.pdf", "b.pdf");
        assertThat(l.ignores()).isEqualTo(1);           // le dossier trop profond
    }

    @Test
    void laListeEstTronquee_auNombreMaximalDeFichiers()
    {
        proprietes.setMaxFichiers(2);
        reponses.put("racine", liste(fichier("a", "1.pdf", "application/pdf", 1L), fichier("b", "2.pdf", "application/pdf", 1L),
            fichier("c", "3.pdf", "application/pdf", 1L)));

        GoogleDriveService.Listage l = service.lister("racine", EXTENSIONS, 1_000_000L);

        assertThat(l.fichiers()).hasSize(2);
        assertThat(l.tronque()).isTrue();
    }

    @Test
    void laClePartDansLaRequeteVersGoogle_maisPasDansLaListe()
    {
        reponses.put("racine", liste());
        service.lister("racine", EXTENSIONS, 1L);

        assertThat(requetes).hasSize(1);
        assertThat(requetes.get(0)).contains("key=CLE-SECRETE-DE-TEST");
    }

    @Test
    void sansCle_ou_avecUnIdentifiantPiege_rienNEstEnvoye()
    {
        proprietes.setApiKey("");
        assertThatThrownBy(() -> service.lister("racine", EXTENSIONS, 1L)).isInstanceOf(BusinessException.class);

        proprietes.setApiKey("K");
        assertThatThrownBy(() -> service.lister("x' or name contains 'a", EXTENSIONS, 1L)).isInstanceOf(BusinessException.class);
        assertThat(requetes).isEmpty();
    }

    @Test
    void lesErreursDeGoogleSontExpliquees()
    {
        statut = 403;
        assertThatThrownBy(() -> service.lister("racine", EXTENSIONS, 1L))
            .hasMessageContaining("Accès refusé").hasMessageContaining("Tous les utilisateurs disposant du lien");
        statut = 404;
        assertThatThrownBy(() -> service.lister("racine", EXTENSIONS, 1L)).hasMessageContaining("introuvable");
    }

    @Test
    void estUrlApiDrive_etAvecCle_neServentQueLHoteDeLApiDrive()
    {
        String base = proprietes.getApiBaseUrl();

        assertThat(service.estUrlApiDrive(URI.create(base + "/drive/v3/files/abc123?alt=media#nom=x.pdf"))).isTrue();
        assertThat(service.estUrlApiDrive(URI.create(base + "/drive/v3/files/abc/export?mimeType=application%2Fpdf"))).isTrue();

        // Autre hôte, autre chemin, autre schéma : jamais la clé.
        assertThat(service.estUrlApiDrive(URI.create("http://127.0.0.1:1/drive/v3/files/abc?alt=media"))).isFalse();
        assertThat(service.estUrlApiDrive(URI.create("https://evil.example/drive/v3/files/abc?alt=media"))).isFalse();
        assertThat(service.estUrlApiDrive(URI.create(base + "/drive/v3/about"))).isFalse();
        assertThat(service.estUrlApiDrive(URI.create(base + "/drive/v3/files/abc/permissions"))).isFalse();
        assertThatThrownBy(() -> service.avecCle(URI.create("https://evil.example/drive/v3/files/abc?alt=media")))
            .isInstanceOf(IllegalArgumentException.class);

        URI avecCle = service.avecCle(URI.create(base + "/drive/v3/files/abc?alt=media#nom=x.pdf"));
        assertThat(avecCle.toString()).contains("alt=media").contains("key=CLE-SECRETE-DE-TEST").doesNotContain("#");
    }

    @Test
    void nomImpose_vientDuFragment_etEstAssaini()
    {
        String base = proprietes.getApiBaseUrl();
        assertThat(service.nomImpose(URI.create(base + "/drive/v3/files/a?alt=media#nom=Relev%C3%A9%20S1.pdf")))
            .contains("Relevé S1.pdf");
        assertThat(service.nomImpose(URI.create(base + "/drive/v3/files/a?alt=media#nom=..%2F..%2Fetc%2Fpasswd")))
            .contains(".._.._etc_passwd");
        assertThat(service.nomImpose(URI.create(base + "/drive/v3/files/a?alt=media"))).isEmpty();
    }

    @Test
    void extraireIdDossier_reconnaitLesLiensDeDossier()
    {
        assertThat(service.extraireIdDossier("https://drive.google.com/drive/folders/1AbC_dEf-9?usp=sharing")).contains("1AbC_dEf-9");
        assertThat(service.extraireIdDossier("https://drive.google.com/drive/u/1/folders/XYZ")).contains("XYZ");
        assertThat(service.extraireIdDossier("https://drive.google.com/file/d/abc/view")).isEmpty();
    }
}
