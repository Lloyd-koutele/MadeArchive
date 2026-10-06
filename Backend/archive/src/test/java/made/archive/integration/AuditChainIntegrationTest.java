package made.archive.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import made.archive.dto.ChaineAuditVerificationDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.JournalAudit;
import made.archive.repository.AuditChainSealRepository;
import made.archive.repository.JournalAuditRepository;
import made.archive.service.audit.AuditChainService;
import made.archive.service.document.HashService;
import made.archive.service.document.HorodatageService;

/**
 * Chaînage en continu (ordre = position attribuée au chaînage, pas l'id) et scellement RFC 3161
 * seulement en cas d'activité — sur un vrai PostgreSQL (verrou consultatif, index unique), TSA simulée.
 */
@Tag("integration")
@SpringBootTest(classes = AuditChainIntegrationTest.Config.class,
                 webEnvironment = SpringBootTest.WebEnvironment.NONE,
                 properties = {
                     "spring.jpa.hibernate.ddl-auto=create-drop",
                     "spring.flyway.enabled=false",
                     "spring.sql.init.mode=never"
                 })
@Testcontainers
class AuditChainIntegrationTest
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
    @Import({ AuditChainService.class, HashService.class })
    static class Config {}

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private AuditChainService        service;
    @Autowired private JournalAuditRepository   journal;
    @Autowired private AuditChainSealRepository sceaux;
    @Autowired private JdbcTemplate             jdbc;

    @MockitoBean private HorodatageService horodatage;
    @MockitoBean private made.archive.service.integrite.HorodatageVerificationService jetons;

    private int compteur;

    @BeforeEach
    void viderEtSimulerTsa()
    {
        sceaux.deleteAll();
        journal.deleteAll();
        reset(horodatage);
        when(horodatage.horodater(anyString()))
            .thenAnswer(i -> new HorodatageService.HorodatageResult(new byte[] { 1, 2, 3 }, Instant.now()));
    }

    private JournalAudit entree()
    {
        compteur++;
        return journal.save(JournalAudit.builder()
            .horodatage(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(compteur))
            .action(AuditAction.DOCUMENT_CONSULTE).cibleType(AuditCible.DOCUMENT).cibleId("doc-" + compteur)
            .description("consultation " + compteur).succes(true).build());
    }

    @Test
    void chainage_attribueDesPositionsConsecutives_etLaChaineEstIntacte()
    {
        entree(); entree(); entree();

        assertThat(service.calculerChainage()).isEqualTo(3);
        assertThat(service.calculerChainage()).isZero(); // idempotent

        List<JournalAudit> chaine = journal.findByPositionChaineIsNotNullOrderByPositionChaineAsc();
        assertThat(chaine).extracting(JournalAudit::getPositionChaine).containsExactly(1L, 2L, 3L);
        assertThat(service.verifierChaine(null).isChaineIntacte()).isTrue();
    }

    @Test
    void entreeValideeEnRetard_avecUnIdInferieur_estChaineeALaSuite_sansFausseRupture()
    {
        // Simule une transaction lente : une entrée d'id ÉLEVÉ est déjà chaînée quand une entrée
        // d'id INFÉRIEUR devient visible. Avec l'ancien ordre (par id), la vérification aurait
        // signalé une rupture qui n'en est pas une.
        jdbc.update("""
            INSERT INTO journal_audit (id, horodatage, action, cible_type, cible_id, description, succes)
            VALUES (1000000, now(), 'DOCUMENT_CONSULTE', 'DOCUMENT', 'doc-rapide', 'transaction rapide', true)
            """);
        service.calculerChainage();

        JournalAudit lente = entree(); // id issu de la séquence, très inférieur à 1 000 000
        assertThat(lente.getId()).isLessThan(1_000_000L);
        service.calculerChainage();

        List<JournalAudit> chaine = journal.findByPositionChaineIsNotNullOrderByPositionChaineAsc();
        assertThat(chaine).extracting(JournalAudit::getId).containsExactly(1_000_000L, lente.getId());
        assertThat(service.verifierChaine(null).isChaineIntacte()).isTrue();
    }

    @Test
    void entreeAlteree_estDetectee()
    {
        entree(); JournalAudit cible = entree(); entree();
        service.calculerChainage();

        jdbc.update("UPDATE journal_audit SET description = 'falsifié' WHERE id = ?", cible.getId());

        ChaineAuditVerificationDto resultat = service.verifierChaine(null);
        assertThat(resultat.isChaineIntacte()).isFalse();
        assertThat(resultat.getRuptures()).extracting(r -> r.getId()).containsExactly(cible.getId());
    }

    @Test
    void scellement_seulementSIlYAEuDeLActivite()
    {
        assertThat(service.scellerSiNecessaire()).isFalse(); // chaîne vide
        verify(horodatage, never()).horodater(anyString());

        entree();
        service.calculerChainage();
        assertThat(service.scellerSiNecessaire()).isTrue();
        assertThat(service.scellerSiNecessaire()).isFalse(); // rien de nouveau : aucun appel TSA
        verify(horodatage, times(1)).horodater(anyString());

        entree();
        service.calculerChainage();
        assertThat(service.scellerSiNecessaire()).isTrue();
        verify(horodatage, times(2)).horodater(anyString());
        assertThat(sceaux.count()).isEqualTo(2);
    }

    @Test
    void echecDeLaTsa_rienNEstEnregistre_etLePassageSuivantReessaie()
    {
        entree();
        service.calculerChainage();

        when(horodatage.horodater(anyString())).thenReturn(null);
        assertThat(service.scellerSiNecessaire()).isFalse();
        assertThat(sceaux.count()).isZero();

        when(horodatage.horodater(anyString()))
            .thenReturn(new HorodatageService.HorodatageResult(new byte[] { 9 }, Instant.now()));
        assertThat(service.scellerSiNecessaire()).isTrue();
        assertThat(sceaux.count()).isEqualTo(1);
    }
}
