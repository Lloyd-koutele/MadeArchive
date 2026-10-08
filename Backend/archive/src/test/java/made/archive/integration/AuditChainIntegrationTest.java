package made.archive.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import made.archive.config.HsmProperties;
import made.archive.dto.ChaineAuditVerificationDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.AuditChainSeal;
import made.archive.entite.JournalAudit;
import made.archive.exception.CleChaineAuditException;
import made.archive.repository.AuditChainConfigRepository;
import made.archive.repository.AuditChainSealRepository;
import made.archive.repository.JournalAuditRepository;
import made.archive.security.HsmKeyStoreService;
import made.archive.security.PkiService;
import made.archive.service.audit.AuditChainService;
import made.archive.service.audit.RegistreScellementsService;
import made.archive.service.document.HashService;
import made.archive.service.document.HorodatageService;
import made.archive.service.integrite.PreuveIntegriteService;

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
    @Import({ AuditChainService.class, HashService.class, HsmKeyStoreService.class, HsmProperties.class,
              RegistreScellementsService.class, PkiService.class })
    static class Config {}

    /** HSM fichier réel (KeyStore PKCS12 temporaire) : la chaîne est testée avec une vraie clé HMAC et de vraies signatures. */
    private static final Path DOSSIER_HSM = creerDossierHsm();

    private static Path creerDossierHsm()
    {
        try
        {
            return Files.createTempDirectory("hsm-audit-test");
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void hsm(DynamicPropertyRegistry registre)
    {
        registre.add("hsm.keystore-path", () -> DOSSIER_HSM.resolve("hsm.p12").toString());
        registre.add("hsm.keystore-password", () -> "mot-de-passe-de-test");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private AuditChainService        service;
    @Autowired private JournalAuditRepository   journal;
    @Autowired private AuditChainSealRepository sceaux;
    @Autowired private JdbcTemplate             jdbc;
    @Autowired private AuditChainConfigRepository configuration;
    @Autowired private HsmKeyStoreService       hsm;
    @Autowired private PkiService               pki;

    @MockitoBean private HorodatageService horodatage;
    @MockitoBean private made.archive.service.integrite.HorodatageVerificationService jetons;

    private int compteur;

    @BeforeEach
    void viderEtSimulerTsa() throws Exception
    {
        sceaux.deleteAll();
        journal.deleteAll();
        configuration.deleteAll();
        Files.deleteIfExists(DOSSIER_HSM.resolve("audit-scellements.registre"));
        if (!hsm.hasKey(PreuveIntegriteService.ALIAS_SYSTEME))
        {
            hsm.storePrivateKey(PreuveIntegriteService.ALIAS_SYSTEME, pki.generateNativeKeyPair());
        }
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

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════
    // Chaînage HMAC (clé du HSM), scellements signés, registre hors base
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════

    private String canonique(JournalAudit e)
    {
        return ReflectionTestUtils.invokeMethod(service, "serialiserCanonique", e);
    }

    private static String sha256(String texte)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texte.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static final char SEP = '\u001F';

    /** Ce que ferait un attaquant qui ne connaît PAS la clé : refaire la chaîne avec l'ancien SHA-256 public. */
    private void reecrireEnSha256Simple(long positionDepart, long positionFin)
    {
        List<JournalAudit> chaine = journal.findByPositionChaineIsNotNullOrderByPositionChaineAsc();
        String precedent = sha256("GENESE-CHAINE-AUDIT-MADEARCHIVE");
        for (JournalAudit e : chaine)
        {
            if (e.getPositionChaine() >= positionDepart && e.getPositionChaine() <= positionFin)
            {
                String hash = sha256(precedent + SEP + canonique(e));
                jdbc.update("UPDATE journal_audit SET chain_hash = ? WHERE id = ?", hash, e.getId());
                precedent = hash;
            }
            else
            {
                precedent = e.getChainHash();
            }
        }
    }

    @Test
    void chainageHmac_uneReecritureCompleteSansLaCleEstDetectee()
    {
        entree(); JournalAudit cible = entree(); entree(); entree();
        service.calculerChainage();
        assertThat(configuration.findById(1L)).isPresent();
        assertThat(configuration.findById(1L).get().getHmacDepuisPosition()).isEqualTo(1L);

        // L'attaquant modifie une entrée puis recalcule TOUTE la suite avec l'algorithme public (SHA-256)
        jdbc.update("UPDATE journal_audit SET description = 'falsifié' WHERE id = ?", cible.getId());
        reecrireEnSha256Simple(2L, Long.MAX_VALUE);

        ChaineAuditVerificationDto resultat = service.verifierChaine(null);
        assertThat(resultat.isChaineIntacte()).isFalse();
        assertThat(resultat.getRuptures()).isNotEmpty();
    }

    @Test
    void historiqueEnSha256_estVerrouilleParLePremierMaillonHmac()
    {
        // Historique d'avant l'évolution : deux entrées chaînées en SHA-256 simple, avant toute configuration HMAC.
        JournalAudit a = entree(); JournalAudit b = entree();
        String h0 = sha256("GENESE-CHAINE-AUDIT-MADEARCHIVE");
        String h1 = sha256(h0 + SEP + canonique(a));
        String h2 = sha256(h1 + SEP + canonique(b));
        jdbc.update("UPDATE journal_audit SET chain_hash = ?, position_chaine = 1 WHERE id = ?", h1, a.getId());
        jdbc.update("UPDATE journal_audit SET chain_hash = ?, position_chaine = 2 WHERE id = ?", h2, b.getId());

        entree();
        service.calculerChainage();
        assertThat(configuration.findById(1L).get().getHmacDepuisPosition()).isEqualTo(3L);
        assertThat(service.verifierChaine(null).isChaineIntacte()).isTrue();

        // Réécrire l'historique SHA-256 (entrées 1 et 2) sans toucher au maillon HMAC : le maillon 3 le trahit.
        jdbc.update("UPDATE journal_audit SET description = 'ancienne entrée falsifiée' WHERE id = ?", a.getId());
        reecrireEnSha256Simple(1L, 2L);
        ChaineAuditVerificationDto resultat = service.verifierChaine(null);
        assertThat(resultat.isChaineIntacte()).isFalse();
        assertThat(resultat.getRuptures()).hasSize(1);          // le maillon HMAC n°3, calculé sur l'ancien historique
    }

    @Test
    void cleDeChainageDifferente_neJugePasLesMaillons_etSuspendLeChainage()
    {
        entree(); entree();
        service.calculerChainage();
        jdbc.update("UPDATE audit_chain_config SET cle_empreinte = 'autre-empreinte'");

        ChaineAuditVerificationDto resultat = service.verifierChaine(null);
        assertThat(resultat.getRuptures()).isEmpty();                 // pas de fausse falsification
        assertThat(resultat.getAnomaliesGlobales()).hasSize(1);
        assertThat(resultat.isChaineIntacte()).isFalse();

        entree();
        assertThatThrownBy(() -> service.calculerChainage()).isInstanceOf(CleChaineAuditException.class);
        assertThat(journal.findByChainHashIsNullOrderByIdAsc()).hasSize(1);   // l'entrée attend, rien n'est chaîné de travers
    }

    @Test
    void cleDeChainageAbsenteDuHsm_estUneAnomalieGlobale_pasUneFalsification()
    {
        entree(); entree();
        service.calculerChainage();
        jdbc.update("UPDATE audit_chain_config SET cle_alias = 'alias-inexistant'");

        ChaineAuditVerificationDto resultat = service.verifierChaine(null);
        assertThat(resultat.getRuptures()).isEmpty();
        assertThat(resultat.getAnomaliesGlobales()).hasSize(1);
    }

    @Test
    void scellementsSignes_etChaines_leRegistreEstTenuHorsBase()
    {
        entree(); service.calculerChainage(); assertThat(service.scellerSiNecessaire()).isTrue();
        entree(); service.calculerChainage(); assertThat(service.scellerSiNecessaire()).isTrue();

        List<AuditChainSeal> liste = sceaux.findAllByOrderByIdAsc();
        assertThat(liste).hasSize(2);
        assertThat(liste).allSatisfy(s ->
        {
            assertThat(s.getSignature()).isNotBlank();
            assertThat(s.getSignatureAlias()).isEqualTo(PreuveIntegriteService.ALIAS_SYSTEME);
            assertThat(s.getDernierPositionChaine()).isNotNull();
        });
        assertThat(liste.get(0).getEmpreintePrecedente()).isNull();
        assertThat(liste.get(1).getEmpreintePrecedente()).isNotBlank();
        assertThat(service.verifierChaine(null).isChaineIntacte()).isTrue();
        assertThat(registreLignes()).isEqualTo(2);
    }

    private long registreLignes()
    {
        try
        {
            return Files.readAllLines(DOSSIER_HSM.resolve("audit-scellements.registre")).stream().filter(l -> !l.isBlank()).count();
        }
        catch (IOException e)
        {
            return -1;
        }
    }

    @Test
    void scellementSupprime_estDetecteGraceAuRegistre()
    {
        entree(); service.calculerChainage(); service.scellerSiNecessaire();
        entree(); service.calculerChainage(); service.scellerSiNecessaire();
        Long dernier = sceaux.findTopByOrderByIdDesc().getId();

        jdbc.update("DELETE FROM audit_chain_seals WHERE id = ?", dernier);     // l'attaquant efface le dernier scellement

        ChaineAuditVerificationDto resultat = service.verifierChaine(null);
        assertThat(resultat.isChaineIntacte()).isFalse();
        assertThat(resultat.getAnomaliesGlobales()).anyMatch(a -> a.contains("DISPARU") && a.contains(String.valueOf(dernier)));
    }

    @Test
    void scellementDuMilieuSupprime_rompLeLien()
    {
        entree(); service.calculerChainage(); service.scellerSiNecessaire();
        entree(); service.calculerChainage(); service.scellerSiNecessaire();
        entree(); service.calculerChainage(); service.scellerSiNecessaire();
        Long milieu = sceaux.findAllByOrderByIdAsc().get(1).getId();

        jdbc.update("DELETE FROM audit_chain_seals WHERE id = ?", milieu);

        ChaineAuditVerificationDto resultat = service.verifierChaine(null);
        assertThat(resultat.isChaineIntacte()).isFalse();
        assertThat(resultat.getRuptures()).anyMatch(r -> r.getDescription().contains("lien avec le scellement précédent"));
        assertThat(resultat.getAnomaliesGlobales()).anyMatch(a -> a.contains("DISPARU"));
    }

    @Test
    void signatureRetireeOuFalsifiee_dUnScellement_estDetectee()
    {
        entree(); service.calculerChainage(); service.scellerSiNecessaire();
        Long id = sceaux.findTopByOrderByIdDesc().getId();

        jdbc.update("UPDATE audit_chain_seals SET signature = NULL WHERE id = ?", id);
        assertThat(service.verifierChaine(null).getRuptures())
            .anyMatch(r -> r.getDescription().contains("signature du système a été retirée"));

        jdbc.update("UPDATE audit_chain_seals SET signature = ? WHERE id = ?", "00".repeat(256), id);
        assertThat(service.verifierChaine(null).getRuptures())
            .anyMatch(r -> r.getDescription().contains("signature du système invalide"));
    }

    @Test
    void scellementFabriqueSansLaCleDuSysteme_estDetecte()
    {
        entree(); service.calculerChainage(); service.scellerSiNecessaire();
        JournalAudit bout = journal.findTopByPositionChaineIsNotNullOrderByPositionChaineDesc();

        // Un faux scellement qui reprend exactement le bout de chaîne, avec une signature inventée
        AuditChainSeal faux = new AuditChainSeal();
        faux.setDernierEntryId(bout.getId());
        faux.setDernierPositionChaine(bout.getPositionChaine());
        faux.setDernierChainHash(bout.getChainHash());
        faux.setEmpreintePrecedente("0".repeat(64));
        faux.setSignatureAlias(PreuveIntegriteService.ALIAS_SYSTEME);
        faux.setSignature("ab".repeat(256));
        sceaux.save(faux);

        ChaineAuditVerificationDto resultat = service.verifierChaine(null);
        assertThat(resultat.isChaineIntacte()).isFalse();
        assertThat(resultat.getRuptures()).anyMatch(r -> r.getDescription().contains("signature du système invalide"));
        assertThat(resultat.getAnomaliesGlobales()).anyMatch(a -> a.contains("absent(s) du registre"));
    }

    @Test
    void registreIntrouvable_alorsQueDesScellementsSignesExistent_estSignale() throws Exception
    {
        entree(); service.calculerChainage(); service.scellerSiNecessaire();
        Files.deleteIfExists(DOSSIER_HSM.resolve("audit-scellements.registre"));

        assertThat(service.verifierChaine(null).getAnomaliesGlobales()).anyMatch(a -> a.contains("introuvable"));
    }

    @Test
    void anomaliesGlobales_nonDetailleesAUnAdminUo()
    {
        entree(); service.calculerChainage(); service.scellerSiNecessaire();
        jdbc.update("DELETE FROM audit_chain_seals");

        ChaineAuditVerificationDto vueAdminUo = service.verifierChaine(java.util.Set.of(42L));
        assertThat(vueAdminUo.getAnomaliesGlobales()).isEmpty();
        assertThat(vueAdminUo.isRupturesHorsPerimetre()).isTrue();
        assertThat(vueAdminUo.isChaineIntacte()).isFalse();
    }
}
