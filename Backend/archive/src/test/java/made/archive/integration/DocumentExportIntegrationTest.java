package made.archive.integration;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
// Spring Boot 4 a éclaté spring-boot-autoconfigure en modules par domaine —
// ces classes ne sont plus dans spring-boot-autoconfigure (vérifié dans les
// jars, pas supposé) : sécurité -> spring-boot-security, Redis ->
// spring-boot-data-redis, @EntityScan -> spring-boot-persistence.
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import made.archive.config.DocumentExportProperties;
import made.archive.dto.ExportApercuDocumentDto;
import made.archive.dto.ExportApercuRequestDto;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.GroupeAccess;
import made.archive.entite.Retention;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeAccess;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.repository.DocumentRepository;
import made.archive.repository.GroupeAccessRepository;
import made.archive.repository.RoleRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.repository.UserRepository;
import made.archive.service.document.DocumentExportGenerationService;
import made.archive.service.document.DocumentExportService;
import made.archive.service.notification.NotificationService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test d'intégration — voir 5.9 et 5.10 du mémoire : exerce la VRAIE requête
 * JPQL (DocumentRepository.findForExport) contre une VRAIE PostgreSQL
 * éphémère (Testcontainers), pas une base H2 de substitution ni des mocks
 * sur les requêtes. Seules les dépendances SANS rapport avec la couche
 * données (autorité UO, notifications, audit, génération asynchrone) sont
 * simulées — le but est de vérifier la jointure UO/status réelle, exactement
 * le genre de régression qu'un mock de repository ne peut pas détecter.
 *
 * Contexte MINIMAL fait à la main (TestJpaConfig ci-dessous), pas
 * l'application complète (qui échouerait à démarrer sans MinIO/Ollama/
 * Meilisearch/HSM réellement disponibles) — @DataJpaTest aurait fait
 * exactement ça, mais Spring Boot 4 a supprimé cette tranche de test
 * (org.springframework.boot.test.autoconfigure.orm.jpa n'existe plus dans
 * spring-boot-test-autoconfigure:4.0.6 — vérifié directement dans le jar).
 */
@Tag("integration")
@SpringBootTest(classes = DocumentExportIntegrationTest.TestJpaConfig.class,
                 webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class DocumentExportIntegrationTest
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
    static class TestJpaConfig
    {
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private DocumentRepository documentRepository;
    @Autowired private UniteOrganisationnelleRepository uoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private TypeDocumentRepository typeDocumentRepository;
    @Autowired private GroupeAccessRepository groupeAccessRepository;

    @Test
    void lApercuNeRenvoieQueLesDocumentsDuPerimetreVisiblesEtVivants()
    {
        // ── Fixture : deux UO, un type de document, un admin ────────────────
        UniteOrganisationnelle uo1 = uoRepository.save(nouvelleUo("UO-Une"));
        UniteOrganisationnelle uo2 = uoRepository.save(nouvelleUo("UO-Deux")); // hors périmètre demandé

        Role roleAdmin = roleRepository.save(new Role(null, Role_Name.ADMIN));
        Role roleEditor = roleRepository.save(new Role(null, Role_Name.EDITOR));

        User admin = userRepository.save(nouvelUtilisateur("admin@test.local", roleAdmin));
        User autreEditeur = userRepository.save(nouvelUtilisateur("autre@test.local", roleEditor));

        TypeDocument type = typeDocumentRepository.save(nouveauTypeDocument(uo1));

        GroupeAccess groupePrive = groupeAccessRepository.save(nouveauGroupe(List.of(autreEditeur)));

        // ── Documents : public (UO1), privé sans l'admin comme membre (UO1),
        //    supprimé (UO1, doit être exclu), et dans une AUTRE UO (UO2, hors
        //    périmètre demandé) ──────────────────────────────────────────────
        Document docPublicUo1  = documentRepository.save(
            nouveauDocument("Public UO1", TypeAccess.PUBLIC, uo1, type, admin, null, DocumentStatus.ACTIVE));
        Document docPriveUo1   = documentRepository.save(
            nouveauDocument("Privé UO1", TypeAccess.PRIVE, uo1, type, admin, groupePrive, DocumentStatus.ACTIVE));
        documentRepository.save(
            nouveauDocument("Supprimé UO1", TypeAccess.PUBLIC, uo1, type, admin, null, DocumentStatus.DELETED));
        documentRepository.save(
            nouveauDocument("Public UO2", TypeAccess.PUBLIC, uo2, type, admin, null, DocumentStatus.ACTIVE));

        // ── Service réel, dépendances hors couche données simulées ──────────
        UniteOrganisationnelleService uoServiceMock = mock(UniteOrganisationnelleService.class);
        when(uoServiceMock.getUoIdsSousAutorite(admin)).thenReturn(null); // ADMIN global

        DocumentExportService service = new DocumentExportService(
            documentRepository,
            mock(made.archive.repository.ExportJobRepository.class),
            uoServiceMock,
            mock(NotificationService.class),
            mock(AuditLogService.class),
            new DocumentExportProperties(),
            mock(DocumentExportGenerationService.class));

        ExportApercuRequestDto requete = new ExportApercuRequestDto();
        requete.setUoIds(List.of(uo1.getId()));
        requete.setExcludeCorbeille(false);

        List<ExportApercuDocumentDto> resultat = service.apercu(requete, admin);

        // Public UO1 visible ; privé UO1 EXCLU (admin n'en est pas membre,
        // pas d'élévation demandée) ; supprimé EXCLU ; UO2 EXCLU (hors périmètre).
        assertThat(resultat)
            .extracting(ExportApercuDocumentDto::getId)
            .containsExactly(docPublicUo1.getId());

        assertThat(resultat)
            .extracting(ExportApercuDocumentDto::getId)
            .doesNotContain(docPriveUo1.getId());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private UniteOrganisationnelle nouvelleUo(String nom)
    {
        UniteOrganisationnelle uo = new UniteOrganisationnelle();
        uo.setNom(nom);
        return uo;
    }

    private User nouvelUtilisateur(String email, Role role)
    {
        User u = new User();
        u.setNom("Nom");
        u.setPrenom("Prenom");
        u.setEmail(email);
        u.setPassword("hash-de-test");
        u.setActif(true);
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        u.setRoles(roles);
        return u;
    }

    private TypeDocument nouveauTypeDocument(UniteOrganisationnelle uo)
    {
        TypeDocument type = new TypeDocument();
        type.setNom("Type de test");
        type.setRetention(new Retention(null, 10L, 0L, null));
        type.setUniteOrganisationnelle(uo);
        return type;
    }

    private GroupeAccess nouveauGroupe(List<User> membres)
    {
        GroupeAccess groupe = new GroupeAccess();
        groupe.setMembres(membres);
        return groupe;
    }

    private Document nouveauDocument(String titre, TypeAccess access, UniteOrganisationnelle uo,
                                      TypeDocument type, User uploadedBy, GroupeAccess groupe,
                                      DocumentStatus status)
    {
        Document d = new Document();
        d.setTitre(titre);
        d.setAccess(access);
        d.setOriginalSha256(UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64));
        d.setPdfaSha256(UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64));
        d.setStorageKey("pdfa/test/" + UUID.randomUUID() + ".pdf");
        d.setStatus(status);
        d.setIntegrityLevel(made.archive.entite.IntegrityLevel.STANDARD);
        d.setUniteOrganisationnelle(uo);
        d.setTypeDocument(type);
        d.setUploadedBy(uploadedBy);
        d.setGroupe(groupe);
        d.setCreateAt(LocalDateTime.now());
        d.setVersion(1L);
        d.setDerniereVersion(true);
        return d;
    }
}
