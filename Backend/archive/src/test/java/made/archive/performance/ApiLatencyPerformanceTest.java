package made.archive.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Mesure du critère de performance « API < 1 s pour les opérations courantes » (mémoire §2.4.3), de bout en bout :
 * client HTTP, Traefik, filtre d'authentification JWT, contrôleur, service, PostgreSQL (et MinIO pour la lecture
 * d'un document).
 *
 * Le test vise une instance DÉJÀ DÉMARRÉE (docker compose) et n'a de sens que sur une machine de développement :
 *  - perf.api.baseUrl : adresse de l'instance, obligatoirement locale (https://127.0.0.1 ou http://localhost:8080) ;
 *  - perf.api.host    : en-tête Host attendu par Traefik (par défaut madearchive.sn) ;
 *  - variables d'environnement PERF_API_EMAIL et PERF_API_PASSWORD : compte de test (jamais écrit dans le code).
 * Sans ces paramètres, ou si l'adresse n'est pas locale, le test est ignoré. Le certificat auto-signé de Traefik
 * n'est accepté que pour une adresse locale.
 *
 * Chaque opération est exécutée après un passage de chauffe, puis N fois de suite ; on relève médiane, 95e
 * percentile et maximum, écrits dans build/perf/api.json pour être reportés dans le mémoire. Seuil (95e percentile) :
 * -Dperf.api.maxMs=1000.
 */
@Tag("performance")
class ApiLatencyPerformanceTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CHAUFFE = 3;
    private static final int MESURES = 40;

    private HttpClient client;
    private String base;
    private String host;

    private static boolean adresseLocale(String url)
    {
        String h = URI.create(url).getHost();
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "[::1]".equals(h) || (h != null && h.endsWith(".localhost"));
    }

    private HttpRequest.Builder requete(String chemin)
    {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + chemin)).timeout(Duration.ofSeconds(30));
        if (host != null && !host.isBlank()) b.header("Host", host);
        return b;
    }

    private HttpResponse<String> envoyer(HttpRequest r)
    {
        try { return client.send(r, HttpResponse.BodyHandlers.ofString()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private record Stat(long mediane, long p95, long max, long min, int echecs) {}

    private Stat mesurer(Supplier<HttpResponse<?>> operation)
    {
        for (int i = 0; i < CHAUFFE; i++) operation.get();
        List<Long> ms = new ArrayList<>();
        int echecs = 0;
        for (int i = 0; i < MESURES; i++)
        {
            long t0 = System.nanoTime();
            HttpResponse<?> r = operation.get();
            ms.add((System.nanoTime() - t0) / 1_000_000);
            if (r.statusCode() >= 400) echecs++;
        }
        List<Long> t = new ArrayList<>(ms);
        Collections.sort(t);
        return new Stat(t.get(t.size() / 2), t.get((int) Math.ceil(0.95 * t.size()) - 1), t.get(t.size() - 1), t.get(0), echecs);
    }

    @Test
    void operationsCourantes_p95SousLeSeuil() throws Exception
    {
        String url = System.getProperty("perf.api.baseUrl");
        String email = System.getenv("PERF_API_EMAIL");
        String mdp = System.getenv("PERF_API_PASSWORD");
        assumeTrue(url != null && email != null && mdp != null, "perf.api.baseUrl, PERF_API_EMAIL et PERF_API_PASSWORD requis : mesure ignorée");
        assumeTrue(adresseLocale(url), "Seule une instance locale peut être mesurée (et son certificat auto-signé accepté)");
        base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        host = System.getProperty("perf.api.host", "madearchive.sn");

        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[] {new X509TrustManager()
        {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        client = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(10)).build();

        String corpsLogin = JSON.writeValueAsString(Map.of("email", email, "password", mdp));
        Supplier<HttpResponse<?>> login = () -> envoyer(requete("/api/login").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(corpsLogin)).build());

        HttpResponse<String> ouverture = envoyer(requete("/api/login").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(corpsLogin)).build());
        assertThat(ouverture.statusCode()).as("authentification du compte de test").isEqualTo(200);
        String jwt = JSON.readTree(ouverture.body()).path("token").asText();
        assertThat(jwt).isNotBlank();

        Supplier<HttpResponse<?>> accessibles = () -> envoyer(requete("/api/user/docs/accessibles?page=1&size=10")
            .header("Authorization", "Bearer " + jwt).GET().build());
        JsonNode liste = JSON.readTree(envoyer(requete("/api/user/docs/accessibles?page=1&size=10")
            .header("Authorization", "Bearer " + jwt).GET().build()).body());
        String docId = null;
        for (String champ : new String[] {"documents", "content", "items", "data"})
        {
            if (liste.path(champ).isArray() && liste.path(champ).size() > 0) { docId = liste.path(champ).get(0).path("id").asText(null); break; }
        }

        if (docId == null) docId = System.getProperty("perf.api.documentId");
        System.out.println("[PERF][API] document de mesure : " + (docId == null ? "aucun" : docId));

        Map<String, Stat> resultats = new LinkedHashMap<>();
        resultats.put("Authentification (POST /api/login)", mesurer(login));
        resultats.put("Liste des types de documents", mesurer(() -> envoyer(requete("/api/user/types-documents")
            .header("Authorization", "Bearer " + jwt).GET().build())));
        if (envoyer(requete("/api/user/docs/folders").header("Authorization", "Bearer " + jwt).GET().build()).statusCode() == 200)
        {
            resultats.put("Arborescence des dossiers", mesurer(() -> envoyer(requete("/api/user/docs/folders")
                .header("Authorization", "Bearer " + jwt).GET().build())));
        }
        resultats.put("Liste des documents accessibles (page de 10)", mesurer(accessibles));
        resultats.put("Recherche plein texte (POST /api/user/documents/search)", mesurer(() -> envoyer(requete("/api/user/documents/search")
            .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"document\",\"page\":1,\"hitsPerPage\":20}")).build())));
        if (docId != null)
        {
            final String id = docId;
            resultats.put("Détail d'un document", mesurer(() -> envoyer(requete("/api/user/docs/" + id)
                .header("Authorization", "Bearer " + jwt).GET().build())));
            resultats.put("Consultation du PDF/A (déchiffrement à la volée)", mesurer(() -> envoyer(requete("/api/user/docs/" + id + "/view")
                .header("Authorization", "Bearer " + jwt).GET().build())));
            resultats.put("Vérification publique (GET /api/public/verify/{id})", mesurer(() -> envoyer(requete("/api/public/verify/" + id).GET().build())));
        }

        StringBuilder sb = new StringBuilder("{\"critere\":\"Latence API\",\"mesures_par_operation\":" + MESURES + ",\"documents_en_base\":\"voir mémoire\",\"operations\":[");
        long pireP95 = 0;
        boolean premier = true;
        for (Map.Entry<String, Stat> e : resultats.entrySet())
        {
            Stat s = e.getValue();
            pireP95 = Math.max(pireP95, s.p95());
            sb.append(premier ? "" : ",").append(String.format(java.util.Locale.ROOT,
                "{\"operation\":\"%s\",\"min_ms\":%d,\"mediane_ms\":%d,\"p95_ms\":%d,\"max_ms\":%d,\"echecs_http\":%d}",
                e.getKey(), s.min(), s.mediane(), s.p95(), s.max(), s.echecs()));
            premier = false;
        }
        sb.append("],\"pire_p95_ms\":").append(pireP95).append('}');
        Path sortie = Path.of("build", "perf");
        Files.createDirectories(sortie);
        Files.writeString(sortie.resolve("api.json"), sb.toString());
        System.out.println("[PERF][API] " + sb);

        long totalEchecs = resultats.values().stream().mapToLong(Stat::echecs).sum();
        assertThat(totalEchecs).as("requêtes en erreur HTTP (une mesure sur une erreur ne prouve rien)").isZero();
        long seuil = Long.getLong("perf.api.maxMs", 1000L);
        assertThat(pireP95).as("95e percentile le plus élevé parmi les opérations (ms), seuil %d ms", seuil).isLessThan(seuil);
    }
}
