package made.archive.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.IntegrityLevel;
import made.archive.entite.MotifSuppression;
import made.archive.entite.Retention;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.SortFinal;
import made.archive.entite.TypeAccess;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.PdfAConversionException;
import made.archive.repository.DataTypeRepository;
import made.archive.repository.DocumentRepository;
import made.archive.repository.RoleRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.repository.UserRepository;
import made.archive.security.DocumentEncryptionService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.document.HashService;
import made.archive.service.document.HorodatageService;
import made.archive.service.document.MeilisearchService;
import made.archive.service.document.PdfAConversionService;
import made.archive.service.document.ProcesVerbalEliminationService;
import made.archive.service.document.ProcesVerbalPdfService;
import made.archive.service.storage.StorageService;
import made.archive.service.user.UtilisateurSystemeService;

/**
 * Procès-verbal d'élimination sur une vraie base : un PV par UO et par jour, rattaché aux pierres tombales,
 * type système en sort CONSERVER, jamais regénéré, échec isolé et réessayable, titres et informations dans le PDF, accès restreint.
 * La conversion PDF/A (Ghostscript) est simulée ici — elle est couverte par PdfAConversionService.
 */
@Tag("integration")
@SpringBootTest(classes = ProcesVerbalEliminationIntegrationTest.Config.class,
                 webEnvironment = SpringBootTest.WebEnvironment.NONE,
                 properties = {
                     "spring.jpa.hibernate.ddl-auto=create-drop",
                     "spring.flyway.enabled=false",
                     "spring.sql.init.mode=never"
                 })
@Testcontainers
class ProcesVerbalEliminationIntegrationTest
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
    static class Config
    {
        @Bean PdfAConversionService pdfAConversionService() { return mock(PdfAConversionService.class); }
        @Bean StorageService storageService() { return mock(StorageService.class); }
        @Bean MeilisearchService meilisearchService() { return mock(MeilisearchService.class); }
        @Bean made.archive.service.organisation.UniteOrganisationnelleService uniteOrganisationnelleService()
        {
            return mock(made.archive.service.organisation.UniteOrganisationnelleService.class);
        }
        @Bean HorodatageService horodatageService() { return mock(HorodatageService.class); }
        @Bean made.archive.service.integrite.PreuveIntegriteService preuveIntegriteService() { return mock(made.archive.service.integrite.PreuveIntegriteService.class); }
        @Bean made.archive.service.integrite.ManifestePreuveService manifestePreuveService() { return mock(made.archive.service.integrite.ManifestePreuveService.class); }
        @Bean AuditLogService auditLogService() { return mock(AuditLogService.class); }
        @Bean DocumentEncryptionService documentEncryptionService()
        {
            DocumentEncryptionService m = mock(DocumentEncryptionService.class);
            when(m.encrypt(any())).thenAnswer(i -> i.getArgument(0)); // identité : on relit le PDF clair capturé
            return m;
        }
        @Bean HashService hashService() { return new HashService(); }
        @Bean ProcesVerbalPdfService procesVerbalPdfService() { return new ProcesVerbalPdfService(); }
        @Bean UtilisateurSystemeService utilisateurSystemeService(UserRepository r) { return new UtilisateurSystemeService(r); }

        @Bean
        ProcesVerbalEliminationService procesVerbalEliminationService(
            DocumentRepository documentRepository, TypeDocumentRepository typeDocumentRepository,
            DataTypeRepository dataTypeRepository, UniteOrganisationnelleRepository uoRepository,
            UserRepository userRepository, made.archive.repository.GroupeAccessRepository groupeAccessRepository,
            made.archive.service.organisation.UniteOrganisationnelleService uoService,
            UtilisateurSystemeService systeme, ProcesVerbalPdfService pdf,
            PdfAConversionService pdfA, HashService hash, DocumentEncryptionService chiffrement,
            StorageService storage, MeilisearchService meili, HorodatageService horodatage,
            AuditLogService audit, PlatformTransactionManager tm,
            made.archive.service.integrite.PreuveIntegriteService preuves,
            made.archive.service.integrite.ManifestePreuveService manifestes)
        {
            return new ProcesVerbalEliminationService(documentRepository, typeDocumentRepository, dataTypeRepository,
                uoRepository, userRepository, groupeAccessRepository, uoService, systeme, pdf, pdfA, hash, chiffrement,
                storage, meili, horodatage, audit, tm, preuves, manifestes);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private ProcesVerbalEliminationService service;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private TypeDocumentRepository typeDocumentRepository;
    @Autowired private UniteOrganisationnelleRepository uoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PdfAConversionService pdfAConversionService;
    @Autowired private StorageService storageService;
    @Autowired private AuditLogService auditLogService;
    @Autowired private HorodatageService horodatageService;
    @Autowired private made.archive.service.integrite.PreuveIntegriteService preuveIntegriteService;
    @Autowired private made.archive.service.integrite.ManifestePreuveService manifestePreuveService;
    @Autowired private MeilisearchService meilisearchService;
    @Autowired private made.archive.repository.GroupeAccessRepository groupeAccessRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private made.archive.service.organisation.UniteOrganisationnelleService uniteOrganisationnelleService;

    @BeforeEach
    void init() throws Exception
    {
        reset(pdfAConversionService, storageService, auditLogService, horodatageService, meilisearchService,
            preuveIntegriteService, manifestePreuveService);
        when(pdfAConversionService.convertirEtVerifier(any(), anyString())).thenAnswer(i ->
            new PdfAConversionService.ResultatPdfA(i.getArgument(0), null));
        when(storageService.uploadBytes(any(), anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
    }

    private UniteOrganisationnelle uo(String nom)
    {
        UniteOrganisationnelle u = new UniteOrganisationnelle();
        u.setNom(nom);
        return uoRepository.save(u);
    }

    private User utilisateur(String email)
    {
        Role role = roleRepository.save(new Role(null, Role_Name.EDITOR));
        User u = new User();
        u.setNom("Nom"); u.setPrenom("Prenom"); u.setEmail(email); u.setPassword("x"); u.setActif(true);
        u.setTelephone("+221" + Math.abs(email.hashCode() % 100000000));
        u.setRoles(new java.util.HashSet<>(List.of(role)));
        return userRepository.save(u);
    }

    private TypeDocument type(UniteOrganisationnelle uo, User createur, String nom)
    {
        TypeDocument t = new TypeDocument();
        t.setNom(nom);
        t.setRetention(new Retention(null, 5L, null, LocalDateTime.now(), SortFinal.DETRUIRE));
        t.setUniteOrganisationnelle(uo);
        t.setUser(createur);
        return typeDocumentRepository.save(t);
    }

    private Document pierreTombale(UniteOrganisationnelle uo, TypeDocument type, User uploader, String titreSecret,
                                   MotifSuppression motif, User eliminePar)
    {
        Document d = new Document();
        d.setTitre(titreSecret);
        d.setAccess(TypeAccess.PUBLIC);
        d.setOriginalSha256(UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64));
        d.setPdfaSha256(UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64));
        d.setStorageKey("pdfa/" + UUID.randomUUID() + ".pdf");
        d.setStatus(DocumentStatus.DELETED);
        d.setIntegrityLevel(IntegrityLevel.STANDARD);
        d.setUniteOrganisationnelle(uo);
        d.setTypeDocument(type);
        d.setUploadedBy(uploader);
        d.setCreateAt(LocalDateTime.of(2020, 1, 15, 10, 0));
        d.setVersion(1L);
        d.setDerniereVersion(true);
        d.setMotifSuppression(motif);
        d.setElimineLe(Instant.now());
        d.setEliminePar(eliminePar != null ? eliminePar.getId() : null);
        return documentRepository.save(d);
    }

    @Test
    void unPvParUoEtParJour_avecTitres_accesRestreint_typeSystemeConserver_etNonRegenere() throws Exception
    {
        UniteOrganisationnelle uo1 = uo("UO-Un");
        UniteOrganisationnelle uo2 = uo("UO-Deux");
        User ed = utilisateur("pv@test.local");
        TypeDocument t1 = type(uo1, ed, "Facture");
        TypeDocument t2 = type(uo2, ed, "Contrat");
        User responsable = utilisateur("responsable@test.local");
        when(uniteOrganisationnelleService.getAdminUOAvecAutoriteSur(uo1.getId())).thenReturn(List.of(responsable));
        when(uniteOrganisationnelleService.getAdminUOAvecAutoriteSur(uo2.getId())).thenReturn(List.of());

        Document a = pierreTombale(uo1, t1, ed, "CV de Mamadou Diop.pdf", MotifSuppression.FIN_DE_VIE, null);
        Document b = pierreTombale(uo1, t1, ed, "Relevé secret.pdf", MotifSuppression.ERREUR_ARCHIVAGE, ed);
        Document c = pierreTombale(uo2, t2, ed, "Autre UO.pdf", MotifSuppression.SUPPRESSION_LEGALE, ed);

        service.genererProcesVerbauxEnAttente();

        Document a2 = documentRepository.findById(a.getId()).orElseThrow();
        Document b2 = documentRepository.findById(b.getId()).orElseThrow();
        Document c2 = documentRepository.findById(c.getId()).orElseThrow();
        assertThat(a2.getProcesVerbalId()).isNotNull().isEqualTo(b2.getProcesVerbalId());   // même UO, même jour
        assertThat(c2.getProcesVerbalId()).isNotNull().isNotEqualTo(a2.getProcesVerbalId()); // autre UO : autre PV

        Document pv = documentRepository.findById(a2.getProcesVerbalId()).orElseThrow();
        assertThat(pv.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(pv.getRetentionUntil()).isNull();                       // jamais d'échéance
        assertThat(pv.getUniteOrganisationnelle().getId()).isEqualTo(uo1.getId());
        assertThat(pv.getUploadedBy().getId())
            .isEqualTo(userRepository.findByEmail(UtilisateurSystemeService.EMAIL).orElseThrow().getId());
        assertThat(pv.getTitre()).contains("2 documents");

        // Le PV reproduit des titres : accès restreint aux responsables de l'UO (jamais public dans l'UO)
        assertThat(pv.getAccess()).isEqualTo(TypeAccess.PRIVE);
        assertThat(pv.getGroupe()).isNotNull();
        List<String> emailsDuGroupe = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
            .execute(status -> groupeAccessRepository.findById(pv.getGroupe().getId()).orElseThrow()
                .getMembres().stream().map(User::getEmail).toList());
        assertThat(emailsDuGroupe).containsExactly("responsable@test.local");

        TypeDocument typePv = typeDocumentRepository.findById(pv.getTypeDocument().getId()).orElseThrow();
        assertThat(typePv.isSysteme()).isTrue();
        assertThat(typePv.getRetention().getSortFinal()).isEqualTo(SortFinal.CONSERVER);
        assertThat(typePv.getRetention().getRetentionYears()).isNull();

        // Le PDF archivé : titre et informations de chaque document, motifs et auteur
        ArgumentCaptor<byte[]> pdfSource = ArgumentCaptor.forClass(byte[].class);
        verify(pdfAConversionService, times(2)).convertirEtVerifier(pdfSource.capture(), anyString());
        String texte;
        try (PDDocument d = PDDocument.load(pdfSource.getAllValues().get(0)))
        {
            texte = new PDFTextStripper().getText(d);
        }
        assertThat(texte).contains(a.getId().toString(), b.getId().toString(), "fin de vie du document",
            "erreur d'archivage", "le système (suppression automatique)", "pv@test.local");
        assertThat(texte).contains("CV de Mamadou Diop.pdf", "Relevé secret.pdf").doesNotContain("Autre UO.pdf");

        // Indexé avec les titres, pour retrouver le PV à partir du document qu'il concerne
        ArgumentCaptor<String> texteIndexe = ArgumentCaptor.forClass(String.class);
        verify(meilisearchService, times(2)).indexDocument(any(), texteIndexe.capture(), any());
        assertThat(texteIndexe.getAllValues().get(0)).contains("CV de Mamadou Diop.pdf", "Relevé secret.pdf");

        verify(horodatageService, times(2)).horodaterApresUpload(any());
        // Chaque PV est scellé par la clé du système (pas d'éditeur) et copié dans le bucket de preuves
        verify(preuveIntegriteService, times(2)).scellerParLeSysteme(any());
        verify(manifestePreuveService, times(2)).ecrire(any());
        verify(auditLogService, times(2)).log(any(), eq(made.archive.entite.AuditAction.PV_ELIMINATION_GENERE),
            any(), anyString(), any(), anyString(), eq(true), any());
        verify(auditLogService, times(3)).log(any(), eq(made.archive.entite.AuditAction.DOCUMENT_INCLUS_PV_ELIMINATION),
            any(), anyString(), any(), anyString(), eq(true), any());

        // Deuxième passage : rien de nouveau, aucun PV regénéré
        reset(pdfAConversionService);
        service.genererProcesVerbauxEnAttente();
        verify(pdfAConversionService, never()).convertirEtVerifier(any(), anyString());
    }

    @Test
    void unEchecDeConversion_nEmpechePasLesAutresGroupes_etLeGroupeEnEchecEstReessaye() throws Exception
    {
        UniteOrganisationnelle ok = uo("UO-Ok");
        UniteOrganisationnelle ko = uo("UO-Ko");
        User ed = utilisateur("echec@test.local");
        Document dOk = pierreTombale(ok, type(ok, ed, "Type A"), ed, "x", MotifSuppression.AUTRE, ed);
        Document dKo = pierreTombale(ko, type(ko, ed, "Type B"), ed, "y", MotifSuppression.AUTRE, ed);

        // doAnswer().when() et non when().thenAnswer() : ce dernier exécuterait l'ancien comportement pendant le stubbing
        doAnswer(i ->
        {
            // Échec pour le PV de l'UO « UO-Ko » (reconnu à son nom dans le PDF source)
            byte[] src = i.getArgument(0);
            try (PDDocument d = PDDocument.load(src))
            {
                if (new PDFTextStripper().getText(d).contains("UO-Ko")) throw new PdfAConversionException("Ghostscript indisponible");
            }
            return new PdfAConversionService.ResultatPdfA(src, null);
        }).when(pdfAConversionService).convertirEtVerifier(any(), anyString());

        service.genererProcesVerbauxEnAttente();

        assertThat(documentRepository.findById(dOk.getId()).orElseThrow().getProcesVerbalId()).isNotNull();
        assertThat(documentRepository.findById(dKo.getId()).orElseThrow().getProcesVerbalId()).isNull(); // rien d'écrit pour lui

        // La panne disparaît : le groupe en échec est repris à la passe suivante
        doAnswer(i -> new PdfAConversionService.ResultatPdfA(i.getArgument(0), null))
            .when(pdfAConversionService).convertirEtVerifier(any(), anyString());
        service.genererProcesVerbauxEnAttente();
        assertThat(documentRepository.findById(dKo.getId()).orElseThrow().getProcesVerbalId()).isNotNull();
    }

    @Test
    void lesAnciennesPierresTombalesSansDateDEliminationNeRecoiventPasDePv()
    {
        UniteOrganisationnelle uo = uo("UO-Ancienne");
        User ed = utilisateur("ancien@test.local");
        Document ancien = pierreTombale(uo, type(uo, ed, "Type C"), ed, "z", null, null);
        ancien.setElimineLe(null); // purgée avant la mise en place des procès-verbaux
        documentRepository.save(ancien);

        service.genererProcesVerbauxEnAttente();

        assertThat(documentRepository.findById(ancien.getId()).orElseThrow().getProcesVerbalId()).isNull();
    }
}
