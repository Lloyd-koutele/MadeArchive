package made.archive.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.Retention;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.SortFinal;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.repository.DocumentRepository;
import made.archive.repository.RoleRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.repository.UserRepository;

/**
 * Tenue en charge de la couche de données : les requêtes de l'API qui parcourent la table des documents
 * (liste paginée d'une unité, recherche par titre, détection de doublon, lecture par identifiant) restent-elles
 * sous 1 seconde quand le catalogue compte des dizaines ou des centaines de milliers de documents ?
 *
 * Un vrai PostgreSQL 16 (Testcontainers, nécessite Docker) reçoit le schéma des entités, puis N documents générés
 * directement en SQL (-Dperf.db.documents=100000 par défaut). Les requêtes sont exécutées par le vrai
 * DocumentRepository (mêmes Specification, tri et pagination que l'API). Chaque opération est mesurée après chauffe,
 * 30 fois ; médiane, 95e percentile et maximum sont écrits dans build/perf/db.json. Seuil : -Dperf.db.maxMs=1000.
 */
@Tag("performance")
@SpringBootTest(classes = DocumentVolumePerformanceTest.Config.class,
                 webEnvironment = SpringBootTest.WebEnvironment.NONE,
                 properties = {
                     "spring.jpa.hibernate.ddl-auto=create-drop",
                     "spring.flyway.enabled=false",
                     "spring.sql.init.mode=never",
                     "spring.jpa.open-in-view=false"
                 })
@Testcontainers
class DocumentVolumePerformanceTest
{
    @Configuration
    @EnableAutoConfiguration(exclude = {
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        DataRedisAutoConfiguration.class,
        DataRedisReactiveAutoConfiguration.class
    })
    @EntityScan(basePackages = "made.archive.entite")
    @EnableJpaRepositories(basePackages = "made.archive.repository")
    static class Config {}

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final int CHAUFFE = 3;
    private static final int MESURES = 30;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DocumentRepository documents;
    @Autowired private UniteOrganisationnelleRepository uoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private TypeDocumentRepository typeRepository;

    private record Stat(long min, long mediane, long p95, long max) {}

    private static Stat mesurer(Supplier<?> op)
    {
        for (int i = 0; i < CHAUFFE; i++) op.get();
        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < MESURES; i++)
        {
            long t0 = System.nanoTime();
            op.get();
            ms.add((System.nanoTime() - t0) / 1_000_000);
        }
        Collections.sort(ms);
        return new Stat(ms.get(0), ms.get(ms.size() / 2), ms.get((int) Math.ceil(0.95 * ms.size()) - 1), ms.get(ms.size() - 1));
    }

    @Test
    void requetesSurLeCatalogue_restentSousLeSeuil() throws Exception
    {
        int n = Integer.getInteger("perf.db.documents", 100_000);

        UniteOrganisationnelle uo = new UniteOrganisationnelle();
        uo.setNom("UO-Charge");
        uo = uoRepository.save(uo);
        Role role = roleRepository.save(new Role(null, Role_Name.EDITOR));
        User u = new User();
        u.setNom("Nom"); u.setPrenom("Prenom"); u.setEmail("charge@test.local"); u.setPassword("x"); u.setActif(true);
        u.setTelephone("+221700000000");
        u.setRoles(new HashSet<>(List.of(role)));
        u = userRepository.save(u);
        TypeDocument t = new TypeDocument();
        t.setNom("Bulletin");
        t.setRetention(new Retention(null, 5L, null, LocalDateTime.now(), SortFinal.DETRUIRE));
        t.setUniteOrganisationnelle(uo);
        t.setUser(u);
        t = typeRepository.save(t);

        long t0 = System.nanoTime();
        jdbc.update("INSERT INTO documents (id, access, create_at, derniere_version, integrity_level, original_sha256, pdfa_sha256, status, "
            + "storage_key, titre, version, type_document_id, uo_id, uploaded_by, elimination_bloquee) "
            + "SELECT gen_random_uuid(), 'PUBLIC', now() - (g || ' minutes')::interval, true, 'STANDARD', md5(g::text) || md5((g + 1)::text), "
            + "md5((g + 2)::text) || md5((g + 3)::text), CASE WHEN g % 20 = 0 THEN 'DELETED' ELSE 'ACTIVE' END, 'pdfa/' || g || '.pdf', "
            + "'Bulletin de notes ' || g, 1, ?, ?, ?, false FROM generate_series(1, ?) g",
            t.getId(), uo.getId(), u.getId(), n);
        jdbc.execute("ANALYZE documents");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[PERF][DB] " + n + " documents générés en " + ms + " ms");
        assertThat(documents.count()).isEqualTo(n);

        final Long uoId = uo.getId();
        Specification<Document> parUo = (root, q, cb) -> cb.and(
            cb.equal(root.get("uniteOrganisationnelle").get("id"), uoId),
            cb.notEqual(root.get("status"), DocumentStatus.DELETED));
        Specification<Document> parTitre = (root, q, cb) -> cb.and(
            cb.equal(root.get("uniteOrganisationnelle").get("id"), uoId),
            cb.like(cb.lower(root.get("titre")), "%bulletin de notes 4242%"));
        String hashExistant = jdbc.queryForObject("SELECT original_sha256 FROM documents WHERE storage_key = 'pdfa/777.pdf'", String.class);
        UUID idExistant = jdbc.queryForObject("SELECT id FROM documents WHERE storage_key = 'pdfa/888.pdf'", UUID.class);

        Map<String, Stat> r = new LinkedHashMap<>();
        r.put("Liste d'une unité, 1re page de 10 (tri par date + total)", mesurer(() ->
            documents.findAll(parUo, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createAt")))));
        r.put("Liste d'une unité, page profonde (offset 5 000)", mesurer(() ->
            documents.findAll(parUo, PageRequest.of(500, 10, Sort.by(Sort.Direction.DESC, "createAt")))));
        r.put("Recherche par titre (repli sans Meilisearch)", mesurer(() ->
            documents.findAll(parTitre, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createAt")))));
        r.put("Détection de doublon (SHA-256 + unité)", mesurer(() ->
            documents.existsByOriginalSha256AndUniteOrganisationnelle_Id(hashExistant, uoId)));
        r.put("Lecture d'un document par identifiant", mesurer(() -> documents.findById(idExistant)));

        StringBuilder sb = new StringBuilder("{\"critere\":\"Requêtes sur le catalogue\",\"documents\":" + n + ",\"mesures\":" + MESURES + ",\"operations\":[");
        long pire = 0; boolean premier = true;
        for (Map.Entry<String, Stat> e : r.entrySet())
        {
            Stat s = e.getValue();
            pire = Math.max(pire, s.p95());
            sb.append(premier ? "" : ",").append(String.format(java.util.Locale.ROOT,
                "{\"operation\":\"%s\",\"min_ms\":%d,\"mediane_ms\":%d,\"p95_ms\":%d,\"max_ms\":%d}", e.getKey(), s.min(), s.mediane(), s.p95(), s.max()));
            premier = false;
        }
        sb.append("],\"pire_p95_ms\":").append(pire).append('}');
        Path sortie = Path.of("build", "perf");
        Files.createDirectories(sortie);
        Files.writeString(sortie.resolve("db-" + n + ".json"), sb.toString());
        System.out.println("[PERF][DB] " + sb);

        long seuil = Long.getLong("perf.db.maxMs", 1000L);
        assertThat(pire).as("95e percentile le plus élevé (ms) avec %d documents, seuil %d ms", n, seuil).isLessThan(seuil);
    }
}
