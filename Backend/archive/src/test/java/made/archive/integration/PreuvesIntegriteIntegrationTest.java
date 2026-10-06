package made.archive.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import made.archive.config.HsmProperties;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditChainSeal;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.IntegrityLevel;
import made.archive.entite.JournalAudit;
import made.archive.entite.Retention;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.SortFinal;
import made.archive.entite.TypeAccess;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.repository.AncrageCatalogueRepository;
import made.archive.repository.AuditChainSealRepository;
import made.archive.repository.DocumentRepository;
import made.archive.repository.JournalAuditRepository;
import made.archive.repository.RoleRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.repository.UserRepository;
import made.archive.security.HsmKeyStoreService;
import made.archive.security.PkiService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.document.BlockchainService;
import made.archive.service.document.HashService;
import made.archive.service.document.HorodatageService;
import made.archive.service.integrite.CatalogueAncrageService;
import made.archive.service.integrite.HorodatageVerificationService;
import made.archive.service.integrite.HorodatageVerificationService.Etat;
import made.archive.service.integrite.PreuveIntegriteService;

/**
 * Sur un vrai PostgreSQL : les déclencheurs de la migration V11 (preuves figées, journal et scellements en ajout
 * seul) et l'ancrage quotidien du catalogue (racine de Merkle signée, chaînée, vérifiable).
 */
@Tag("integration")
@SpringBootTest(classes = PreuvesIntegriteIntegrationTest.Config.class,
                 webEnvironment = SpringBootTest.WebEnvironment.NONE,
                 properties = {
                     "spring.jpa.hibernate.ddl-auto=create-drop",
                     "spring.flyway.enabled=false",
                     "spring.sql.init.mode=never"
                 })
@Testcontainers
class PreuvesIntegriteIntegrationTest
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
    @Import({ CatalogueAncrageService.class, HashService.class })
    static class Config
    {
        @Bean HsmKeyStoreService hsm() throws Exception
        {
            Path dossier = Files.createTempDirectory("hsm-test");
            HsmProperties props = new HsmProperties();
            props.setKeystorePath(dossier.resolve("hsm.p12").toString());
            props.setKeystorePassword("mot-de-passe-de-test");
            HsmKeyStoreService hsm = new HsmKeyStoreService(props);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(hsm, "init");
            hsm.storePrivateKey(PreuveIntegriteService.ALIAS_SYSTEME, new PkiService().generateNativeKeyPair());
            return hsm;
        }
        @Bean AuditLogService auditLogService() { return mock(AuditLogService.class); }
        @Bean BlockchainService blockchainService() { return mock(BlockchainService.class); }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private CatalogueAncrageService ancrage;
    @Autowired private AncrageCatalogueRepository ancrages;
    @Autowired private DocumentRepository documents;
    @Autowired private JournalAuditRepository journal;
    @Autowired private AuditChainSealRepository sceaux;
    @Autowired private UniteOrganisationnelleRepository uoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private HorodatageService horodatage;
    @MockitoBean private HorodatageVerificationService jetons;

    private static boolean declencheursInstalles;
    private static UniteOrganisationnelleAndCo contexte;
    private int n;

    private record UniteOrganisationnelleAndCo(UniteOrganisationnelle uo, User user, TypeDocument type) {}

    @BeforeEach
    void preparer() throws Exception
    {
        if (!declencheursInstalles)
        {
            // pgjdbc découpe lui-même les instructions, dollar-quoting compris
            jdbc.execute(new String(getClass().getResourceAsStream("/db/migration/V11__protection_preuves_triggers.sql")
                .readAllBytes(), StandardCharsets.UTF_8));
            declencheursInstalles = true;
        }
        reset(horodatage, jetons);
        when(horodatage.horodater(anyString()))
            .thenAnswer(i -> new HorodatageService.HorodatageResult(new byte[] { 9, 9 }, Instant.now()));
        when(jetons.verifier(any(), any())).thenReturn(Etat.ATTESTE_ANCRE);

        withTriggers(false);
        jdbc.execute("DELETE FROM audit_chain_seals");
        jdbc.execute("DELETE FROM ancrages_catalogue");
        jdbc.execute("DELETE FROM journal_audit");
        jdbc.execute("DELETE FROM documents");
        withTriggers(true);

        if (contexte == null)
        {
            UniteOrganisationnelle uo = new UniteOrganisationnelle();
            uo.setNom("UO-Preuves");
            uo = uoRepository.save(uo);
            Role role = roleRepository.save(new Role(null, Role_Name.EDITOR));
            User u = new User();
            u.setNom("Nom"); u.setPrenom("Prenom"); u.setEmail("preuves@test.local"); u.setPassword("x"); u.setActif(true);
            u.setTelephone("+221700000001");
            u.setRoles(new HashSet<>(List.of(role)));
            u = userRepository.save(u);
            TypeDocument t = new TypeDocument();
            t.setNom("Facture");
            t.setRetention(new Retention(null, 5L, null, LocalDateTime.now(), SortFinal.DETRUIRE));
            t.setUniteOrganisationnelle(uo);
            t.setUser(u);
            contexte = new UniteOrganisationnelleAndCo(uo, u, typeRepository.save(t));
        }
    }

    private void withTriggers(boolean actifs)
    {
        String action = actifs ? "ENABLE" : "DISABLE";
        for (String[] t : new String[][] {
            { "documents", "trg_proteger_preuves_documents" }, { "journal_audit", "trg_proteger_journal_audit" },
            { "audit_chain_seals", "trg_proteger_audit_chain_seals" }, { "ancrages_catalogue", "trg_proteger_ancrages_catalogue" } })
        {
            jdbc.execute("ALTER TABLE " + t[0] + " " + action + " TRIGGER " + t[1]);
        }
    }

    private Document document()
    {
        n++;
        Document d = new Document();
        d.setTitre("Doc " + n);
        d.setAccess(TypeAccess.PUBLIC);
        d.setOriginalSha256(UUID.randomUUID().toString().replace("-", "").repeat(2));
        d.setPdfaSha256(UUID.randomUUID().toString().replace("-", "").repeat(2));
        d.setStorageKey("pdfa/" + UUID.randomUUID() + ".pdf");
        d.setPkiSignature("aa".repeat(256));
        d.setSignatureEnregistrement("bb".repeat(256));
        d.setSignatureEnregistrementAlias("editor-x");
        d.setStatus(DocumentStatus.ACTIVE);
        d.setIntegrityLevel(IntegrityLevel.STANDARD);
        d.setUniteOrganisationnelle(contexte.uo());
        d.setTypeDocument(contexte.type());
        d.setUploadedBy(contexte.user());
        d.setCreateAt(LocalDateTime.of(2026, 1, 1, 10, 0).plusMinutes(n));
        d.setVersion(1L);
        d.setDerniereVersion(true);
        return documents.save(d);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════
    // Déclencheurs
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void documents_lesPreuvesSontFigees_maisLeResteSeModifie()
    {
        Document d = document();

        jdbc.update("UPDATE documents SET titre = 'Nouveau titre' WHERE id = ?", d.getId());                    // légitime
        jdbc.update("UPDATE documents SET horodatage_token = ?, horodatage_date = now() WHERE id = ?",
            new byte[] { 1 }, d.getId());                                                                          // null → valeur

        for (String colonne : List.of("pdfa_sha256", "original_sha256", "storage_key", "pki_signature",
            "signature_enregistrement", "horodatage_token"))
        {
            Object valeur = colonne.equals("horodatage_token") ? new byte[] { 2 } : "modifie-" + colonne;
            assertThatThrownBy(() -> jdbc.update("UPDATE documents SET " + colonne + " = ? WHERE id = ?", valeur, d.getId()))
                .as(colonne).hasMessageContaining("Modification interdite");
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE documents SET version = 9 WHERE id = ?", d.getId()))
            .hasMessageContaining("Modification interdite");
        assertThatThrownBy(() -> jdbc.update("UPDATE documents SET create_at = now() WHERE id = ?", d.getId()))
            .hasMessageContaining("Modification interdite");
    }

    @Test
    void journal_seulLeChainageSeRenseigne_etRienNeSeSupprime()
    {
        JournalAudit e = journal.save(JournalAudit.builder().horodatage(Instant.now()).action(AuditAction.DOCUMENT_CONSULTE)
            .cibleType(AuditCible.DOCUMENT).cibleId("x").description("consultation").succes(true).build());

        jdbc.update("UPDATE journal_audit SET chain_hash = 'h1', position_chaine = 1 WHERE id = ?", e.getId()); // chaînage

        assertThatThrownBy(() -> jdbc.update("UPDATE journal_audit SET description = 'réécrit' WHERE id = ?", e.getId()))
            .hasMessageContaining("Modification interdite");
        assertThatThrownBy(() -> jdbc.update("UPDATE journal_audit SET chain_hash = 'h2' WHERE id = ?", e.getId()))
            .hasMessageContaining("Modification interdite");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_audit WHERE id = ?", e.getId()))
            .hasMessageContaining("Suppression interdite");
    }

    @Test
    void scellementsEtAncrages_ajoutSeul_seulLeJetonSeCompleteUneFois()
    {
        AuditChainSeal s = new AuditChainSeal();
        s.setDernierEntryId(1L);
        s.setDernierChainHash("h");
        s = sceaux.save(s);
        Long id = s.getId();

        jdbc.update("UPDATE audit_chain_seals SET horodatage_token = ?, horodatage_date = now() WHERE id = ?", new byte[] { 1 }, id);

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_chain_seals SET dernier_chain_hash = 'autre' WHERE id = ?", id))
            .hasMessageContaining("Modification interdite");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_chain_seals SET horodatage_token = ? WHERE id = ?", new byte[] { 2 }, id))
            .hasMessageContaining("Modification interdite");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_chain_seals WHERE id = ?", id))
            .hasMessageContaining("Suppression interdite");
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════
    // Ancrage du catalogue
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void ancrage_rattacheLesDocumentsSceles_chaineLesLots_etSeVerifie()
    {
        List<Document> premier = List.of(document(), document(), document());

        var a1 = ancrage.ancrer().orElseThrow();
        assertThat(a1.getNombreDocuments()).isEqualTo(3);
        assertThat(a1.getRacinePrecedente()).isNull();
        assertThat(a1.getHorodatageToken()).isNotNull();   // jeton RFC 3161 obtenu
        premier.forEach(d -> assertThat(documents.findById(d.getId()).orElseThrow().getAncrageId()).isEqualTo(a1.getId()));
        assertThat(ancrage.ancrer()).isEmpty();            // rien de nouveau : pas d'ancrage vide

        document(); document();
        var a2 = ancrage.ancrer().orElseThrow();
        assertThat(a2.getRacinePrecedente()).isEqualTo(a1.getRacineMerkle());   // chaînage des ancrages

        CatalogueAncrageService.Rapport rapport = ancrage.verifier();
        assertThat(rapport.intact()).as(String.valueOf(rapport.anomalies())).isTrue();
        assertThat(rapport.ancrages()).isEqualTo(2);
        assertThat(rapport.documentsAncres()).isEqualTo(5);
    }

    @Test
    void ancrage_ignoreLesDocumentsNonScelles()
    {
        Document d = document();
        withTriggers(false);
        jdbc.update("UPDATE documents SET signature_enregistrement = NULL WHERE id = ?", d.getId());
        withTriggers(true);

        assertThat(ancrage.ancrer()).isEmpty();
    }

    @Test
    void verification_detecteLaModificationDUneEmpreinteAncree_memeParUnSuperutilisateurQuiContourneLesDeclencheurs()
    {
        Document d = document();
        document();
        ancrage.ancrer().orElseThrow();
        assertThat(ancrage.verifier().intact()).isTrue();

        // Un superutilisateur désactive le déclencheur et réécrit l'empreinte
        withTriggers(false);
        jdbc.update("UPDATE documents SET pdfa_sha256 = ? WHERE id = ?", "f".repeat(64), d.getId());
        withTriggers(true);

        CatalogueAncrageService.Rapport rapport = ancrage.verifier();
        assertThat(rapport.intact()).isFalse();
        assertThat(rapport.anomalies()).anyMatch(a -> a.contains("racine recalculée"));
    }

    @Test
    void verification_detecteUnDocumentRetireOuAjouteAUnLotAncre()
    {
        Document d = document();
        document();
        var a = ancrage.ancrer().orElseThrow();

        withTriggers(false);
        jdbc.update("UPDATE documents SET ancrage_id = NULL WHERE id = ?", d.getId());   // retiré du lot
        withTriggers(true);

        assertThat(ancrage.verifier().anomalies()).anyMatch(s -> s.contains("1 document(s) rattaché(s) au lieu de 2"));
        assertThat(a.getId()).isNotNull();
    }

    @Test
    void verification_detecteLaRacineReecrite_laSignatureDuSystemeEtLeJeton()
    {
        document();
        var a = ancrage.ancrer().orElseThrow();

        withTriggers(false);
        jdbc.update("UPDATE ancrages_catalogue SET racine_merkle = ? WHERE id = ?", "0".repeat(64), a.getId());
        withTriggers(true);
        when(jetons.verifier(any(), any())).thenReturn(Etat.EMPREINTE_DIFFERENTE); // l'autorité a certifié l'AUTRE racine

        List<String> anomalies = ancrage.verifier().anomalies();
        assertThat(anomalies).anyMatch(s -> s.contains("racine recalculée"));
        assertThat(anomalies).anyMatch(s -> s.contains("signature du système invalide"));
        assertThat(anomalies).anyMatch(s -> s.contains("jeton d'horodatage atteste une autre racine"));
    }

    @Test
    void verification_detecteUnAncrageRetireDuMilieuDeLaChaine()
    {
        document();
        ancrage.ancrer().orElseThrow();
        document();
        var a2 = ancrage.ancrer().orElseThrow();
        document();
        ancrage.ancrer().orElseThrow();

        withTriggers(false);
        jdbc.update("UPDATE documents SET ancrage_id = NULL WHERE ancrage_id = ?", a2.getId());
        jdbc.update("DELETE FROM ancrages_catalogue WHERE id = ?", a2.getId());
        withTriggers(true);

        assertThat(ancrage.verifier().anomalies()).anyMatch(s -> s.contains("lien avec l'ancrage précédent est rompu"));
    }

    @Test
    void completerHorodatages_reprendLesJetonsManquants()
    {
        when(horodatage.horodater(anyString())).thenReturn(null); // TSA injoignable à l'ancrage
        document();
        var a = ancrage.ancrer().orElseThrow();
        assertThat(a.getHorodatageToken()).isNull();

        when(horodatage.horodater(anyString()))
            .thenAnswer(i -> new HorodatageService.HorodatageResult(new byte[] { 7 }, Instant.now()));
        assertThat(ancrage.completerHorodatages()).isEqualTo(1);
        assertThat(ancrages.findById(a.getId()).orElseThrow().getHorodatageToken()).containsExactly(7);
    }
}
