package made.archive.service.importweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import made.archive.exception.BusinessException;

/**
 * Un petit serveur HTTP local joue le rôle du site distant. Pour exercer le reste de la logique, la boucle locale est
 * autorisée par un prédicat injecté ; le comportement RÉEL (boucle locale interdite) est vérifié à part.
 */
@Tag("unit")
class ClientHttpSecuriseTest
{
    private HttpServer serveur;
    private final AtomicLong octetsEnvoyes = new AtomicLong();
    private int port;

    @BeforeEach
    void demarrer() throws Exception
    {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = serveur.getAddress().getPort();

        serveur.createContext("/ok", e ->
        {
            byte[] corps = "bonjour".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "text/plain");
            e.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"a.txt\"");
            e.sendResponseHeaders(200, corps.length);
            e.getResponseBody().write(corps);
            e.close();
        });
        serveur.createContext("/404", e -> { e.sendResponseHeaders(404, -1); e.close(); });
        // Annonce une taille énorme : doit être refusé AVANT de lire quoi que ce soit.
        serveur.createContext("/annonce", e ->
        {
            e.sendResponseHeaders(200, 500L * 1024 * 1024);
            e.close();
        });
        // Flux SANS FIN en transfert fragmenté : le client doit s'arrêter de lui-même.
        serveur.createContext("/infini", e ->
        {
            e.sendResponseHeaders(200, 0);
            byte[] bloc = new byte[8192];
            try (OutputStream o = e.getResponseBody())
            {
                for (int i = 0; i < 100_000; i++)
                {
                    o.write(bloc);
                    o.flush();
                    octetsEnvoyes.addAndGet(bloc.length);
                    Thread.sleep(1);   // cadence réaliste : sans pause, le noyau avale des centaines de Mo avant l'arrêt du client
                }
            }
            catch (IOException | InterruptedException clientParti)
            {
                // attendu : le client a fermé la connexion
            }
        });
        // Chaîne de redirections : /r0 → /r1 → … → /r6 → /ok
        for (int i = 0; i <= 6; i++)
        {
            final String suivant = i == 6 ? "/ok" : "/r" + (i + 1);
            serveur.createContext("/r" + i, e ->
            {
                e.getResponseHeaders().add("Location", suivant);
                e.sendResponseHeaders(302, -1);
                e.close();
            });
        }
        serveur.createContext("/vers-interne", e ->
        {
            e.getResponseHeaders().add("Location", "http://127.0.0.2:" + port + "/ok");
            e.sendResponseHeaders(302, -1);
            e.close();
        });
        serveur.start();
    }

    @AfterEach
    void arreter()
    {
        serveur.stop(0);
    }

    private ClientHttpSecurise clientLoopbackAutorise()
    {
        // 127.0.0.1 autorisé (le serveur de test) ; 127.0.0.2 reste interdit (cible interne simulée)
        return new ClientHttpSecurise(a -> a.getHostAddress().equals("127.0.0.2"));
    }

    private URI url(String chemin)
    {
        return URI.create("http://127.0.0.1:" + port + chemin);
    }

    @Test
    void telechargeUnFichierEtExposeSesEntetes()
    {
        ClientHttpSecurise.Reponse r = clientLoopbackAutorise().telecharger(url("/ok"), 1024);

        assertThat(new String(r.corps(), StandardCharsets.UTF_8)).isEqualTo("bonjour");
        assertThat(r.premierEntete("content-type")).contains("text/plain");
        assertThat(r.premierEntete("Content-Disposition")).hasValueSatisfying(v -> assertThat(v).contains("a.txt"));
    }

    @Test
    void refuseUnCodeHttpEnErreur()
    {
        assertThatThrownBy(() -> clientLoopbackAutorise().telecharger(url("/404"), 1024))
            .isInstanceOf(BusinessException.class).hasMessageContaining("404");
    }

    // ── taille ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void uneTailleAnnonceeTropGrandeEstRefuseeSansRienLire()
    {
        assertThatThrownBy(() -> clientLoopbackAutorise().telecharger(url("/annonce"), 1024 * 1024))
            .isInstanceOf(BusinessException.class).hasMessageContaining("volumineux");
    }

    @Test
    void unFluxSansFinEstCoupeDesLaLimiteDepassee_sansChargerLeFluxEnMemoire() throws Exception
    {
        long limite = 1024 * 1024;
        long debut = System.currentTimeMillis();

        assertThatThrownBy(() -> clientLoopbackAutorise().telecharger(url("/infini"), limite))
            .isInstanceOf(BusinessException.class).hasMessageContaining("volumineux");

        assertThat(System.currentTimeMillis() - debut).as("le client n'attend pas la fin d'un flux infini").isLessThan(10_000);
        long auMomentDeLArret = octetsEnvoyes.get();
        Thread.sleep(500);
        // Le serveur était prêt à envoyer ~800 Mo : le client a coupé la connexion juste après la limite, et le serveur
        // n'a plus rien pu écrire ensuite.
        assertThat(octetsEnvoyes.get()).isLessThan(20L * 1024 * 1024);
        assertThat(octetsEnvoyes.get() - auMomentDeLArret).isLessThan(2L * 1024 * 1024);
    }

    // ── redirections ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void suitJusqua3RedirectionsPuisRefuse()
    {
        ClientHttpSecurise client = clientLoopbackAutorise();

        // /r3 → /r4 → /r5 → /r6 → /ok : 4 sauts → refusé ; /r6 → /ok : 1 saut → accepté
        assertThatThrownBy(() -> client.telecharger(url("/r3"), 1024))
            .isInstanceOf(BusinessException.class).hasMessageContaining("redirections");
        assertThat(client.telecharger(url("/r6"), 1024).corps()).hasSize(7);
        // exactement 3 sauts : /r4 → /r5 → /r6 → /ok
        assertThat(client.telecharger(url("/r4"), 1024).corps()).hasSize(7);
    }

    @Test
    void uneRedirectionVersUneAdresseInterneEstRefusee()
    {
        assertThatThrownBy(() -> clientLoopbackAutorise().telecharger(url("/vers-interne"), 1024))
            .isInstanceOf(LienRefuseException.class).hasMessageContaining("réseau interne");
    }

    // ── adresses et schémas ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void lAdresseIpLitteraleInterditeEstRefuseeAvantLaConnexion()
    {
        assertThatThrownBy(() -> clientLoopbackAutorise().telecharger(URI.create("http://127.0.0.2:" + port + "/ok"), 1024))
            .isInstanceOf(LienRefuseException.class);
    }

    @Test
    void unSchemaAutreQueHttpEstRefuse()
    {
        assertThatThrownBy(() -> clientLoopbackAutorise().telecharger(URI.create("file:///etc/passwd"), 1024))
            .isInstanceOf(LienRefuseException.class);
        assertThatThrownBy(() -> clientLoopbackAutorise().telecharger(URI.create("ftp://example.com/a.pdf"), 1024))
            .isInstanceOf(LienRefuseException.class);
    }

    @Test
    void leComportementReelRefuseLaBoucleLocale()
    {
        // Prédicat de production : le serveur de test lui-même (127.0.0.1) est interdit.
        assertThatThrownBy(() -> new ClientHttpSecurise().telecharger(url("/ok"), 1024))
            .isInstanceOf(LienRefuseException.class).hasMessageContaining("réseau interne");
        assertThatThrownBy(() -> new ClientHttpSecurise().telecharger(URI.create("http://localhost:" + port + "/ok"), 1024))
            .isInstanceOf(LienRefuseException.class);
    }

    /**
     * « DNS rebinding » : un nom qui paraît public à la validation puis interne à la connexion. Simulé avec "localhost" :
     * le prédicat laisse passer les adresses jugées lors du contrôle préalable, puis les interdit — ce qui correspond à
     * la résolution faite par le client au moment de se connecter. Elle doit être refusée, preuve que c'est BIEN ce
     * résultat-là qui est contrôlé, pas seulement celui de la validation.
     */
    @Test
    void uneResolutionQuiChangeEntreLaValidationEtLaConnexionEstRefusee() throws Exception
    {
        int nombreDAdresses = InetAddress.getAllByName("localhost").length;
        AtomicInteger appels = new AtomicInteger();
        ClientHttpSecurise client = new ClientHttpSecurise(a -> appels.incrementAndGet() > nombreDAdresses);

        assertThatThrownBy(() -> client.telecharger(URI.create("http://localhost:" + port + "/ok"), 1024))
            .isInstanceOf(LienRefuseException.class).hasMessageContaining("réseau interne");
        assertThat(appels.get()).isGreaterThan(nombreDAdresses);   // le contrôle de la connexion a bien eu lieu
    }
}
