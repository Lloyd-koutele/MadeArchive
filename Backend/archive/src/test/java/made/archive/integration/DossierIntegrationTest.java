package made.archive.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import made.archive.dto.DossierDto;
import made.archive.dto.UniteOrganisationnelleDto;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.Dossier;
import made.archive.entite.IntegrityLevel;
import made.archive.entite.Retention;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.SortFinal;
import made.archive.entite.TypeAccess;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.DossierRepository;
import made.archive.repository.GroupeAccessRepository;
import made.archive.repository.PhysicalLocationRepository;
import made.archive.repository.RoleRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.document.MeilisearchService;
import made.archive.service.notification.NotificationService;
import made.archive.service.organisation.DossierService;
import made.archive.service.organisation.UniteOrganisationnelleService;

/**
 * Dossiers : suppression avec toute la sous-arborescence, et verrou d'une branche dès qu'un de ses dossiers contient
 * un document (renommer, déplacer, supprimer refusés pour le dossier ET ses parents).
 */
@Tag("integration")
@SpringBootTest(classes = DossierIntegrationTest.Config.class,
                 webEnvironment = SpringBootTest.WebEnvironment.NONE,
                 properties = {
                     "spring.jpa.hibernate.ddl-auto=create-drop",
                     "spring.flyway.enabled=false",
                     "spring.sql.init.mode=never"
                 })
@Testcontainers
class DossierIntegrationTest
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
        @Bean UniteOrganisationnelleService uniteOrganisationnelleService() { return mock(UniteOrganisationnelleService.class); }

        @Bean
        DossierService dossierService(
            DossierRepository dossierRepository, UniteOrganisationnelleRepository uoRepository,
            TypeDocumentRepository typeDocumentRepository, DocumentRepository documentRepository,
            UserRepository userRepository, GroupeAccessRepository groupeAccessRepository,
            UniteOrganisationnelleService uoService, PhysicalLocationRepository physicalLocationRepository)
        {
            return new DossierService(dossierRepository, uoRepository, typeDocumentRepository, documentRepository,
                userRepository, groupeAccessRepository, uoService, mock(NotificationService.class),
                mock(AuditLogService.class), mock(MeilisearchService.class), physicalLocationRepository);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private DossierService service;
    @Autowired private DossierRepository dossierRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private UniteOrganisationnelleRepository uoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private TypeDocumentRepository typeDocumentRepository;
    @Autowired private UniteOrganisationnelleService uoServiceMock;

    private UniteOrganisationnelle uo;
    private User ed;

    private void preparer(String nom)
    {
        uo = new UniteOrganisationnelle();
        uo.setNom(nom);
        uo = uoRepository.save(uo);
        Role role = roleRepository.save(new Role(null, Role_Name.EDITOR));
        User u = new User();
        u.setNom("Nom"); u.setPrenom("Prenom"); u.setEmail(nom + "@test.local"); u.setPassword("x"); u.setActif(true);
        u.setTelephone("+221" + Math.abs(nom.hashCode() % 100000000));
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        u.setRoles(roles);
        ed = userRepository.save(u);
        UniteOrganisationnelleDto dto = new UniteOrganisationnelleDto();
        dto.setId(uo.getId());
        when(uoServiceMock.getUOActuelleUser(any())).thenReturn(Optional.of(dto));
        when(uoServiceMock.getUoIdsVisiblesPourLecture(any())).thenReturn(null);
    }

    private Dossier dossier(String nom, Long parentId)
    {
        DossierDto d = new DossierDto();
        d.setNom(nom);
        d.setUoId(uo.getId());
        d.setParentId(parentId);
        d.setAccess("PUBLIC");
        return service.creerDossier(d, ed);
    }

    private void document(Dossier dossier)
    {
        TypeDocument t = new TypeDocument();
        t.setNom("Type " + UUID.randomUUID());
        t.setRetention(new Retention(null, 5L, null, LocalDateTime.now(), SortFinal.DETRUIRE));
        t.setUniteOrganisationnelle(uo);
        t.setUser(ed);
        t = typeDocumentRepository.save(t);

        Document d = new Document();
        d.setTitre("doc");
        d.setAccess(TypeAccess.PUBLIC);
        d.setOriginalSha256(UUID.randomUUID().toString().replace("-", "").repeat(2));
        d.setPdfaSha256(UUID.randomUUID().toString().replace("-", "").repeat(2));
        d.setStorageKey("pdfa/" + UUID.randomUUID() + ".pdf");
        d.setStatus(DocumentStatus.ACTIVE);
        d.setIntegrityLevel(IntegrityLevel.STANDARD);
        d.setUniteOrganisationnelle(uo);
        d.setTypeDocument(t);
        d.setUploadedBy(ed);
        d.setDossier(dossier);
        d.setCreateAt(LocalDateTime.now());
        d.setVersion(1L);
        d.setDerniereVersion(true);
        documentRepository.save(d);
    }

    @Test
    @Transactional
    void supprimerUnDossierAvecToutesSesSousArborescences_quandAucunDocument()
    {
        preparer("UO-Suppression");
        Dossier annee = dossier("Année 2025-2026", null);
        Dossier master = dossier("Master 1", annee.getId());
        dossier("Semestre 1", master.getId());
        dossier("Semestre 2", master.getId());
        Dossier autre = dossier("Autre", null);

        service.supprimerDossier(annee.getId(), ed);

        assertThat(dossierRepository.findByUniteOrganisationnelleId(uo.getId())).extracting(Dossier::getId)
            .containsExactly(autre.getId());
    }

    @Test
    @Transactional
    void unDocumentDansUnEnfantVerrouilleTouteLaBranche_maisPasLesBranchesSoeurs()
    {
        preparer("UO-Verrou");
        Dossier annee = dossier("Année", null);
        Dossier master = dossier("Master", annee.getId());
        Dossier s1 = dossier("Semestre 1", master.getId());
        Dossier s2 = dossier("Semestre 2", master.getId());
        document(s1);

        // Le dossier qui contient le document ET tous ses parents sont verrouillés
        for (Dossier verrouille : List.of(s1, master, annee))
        {
            assertThatThrownBy(() -> service.modifierDossier(verrouille.getId(), "Autre nom", ed))
                .isInstanceOf(BusinessException.class).hasMessageContaining("renommer");
            assertThatThrownBy(() -> service.deplacerDossier(verrouille.getId(), s2.getId(), ed))
                .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.supprimerDossier(verrouille.getId(), ed))
                .isInstanceOf(BusinessException.class).hasMessageContaining("documents");
        }

        // La sœur sans document reste libre : renommée puis supprimée
        assertThat(service.modifierDossier(s2.getId(), "Semestre 2 bis", ed).getNom()).isEqualTo("Semestre 2 bis");
        service.supprimerDossier(s2.getId(), ed);
        assertThat(dossierRepository.findById(s2.getId())).isEmpty();

        // Renvoyer le MÊME nom (formulaire qui ne change que les types attendus) n'est pas un renommage
        assertThat(service.modifierDossier(master.getId(), "Master", ed).getNom()).isEqualTo("Master");
    }

    @Test
    @Transactional
    void lesListesIndiquentLesBranchesVerrouillees()
    {
        preparer("UO-Liste");
        Dossier a = dossier("A", null);
        Dossier a1 = dossier("A1", a.getId());
        Dossier b = dossier("B", null);
        document(a1);

        List<Dossier> racines = service.getDossiersDeUO(uo.getId(), null, ed);

        assertThat(racines).filteredOn(d -> d.getId().equals(a.getId())).singleElement().satisfies(d -> assertThat(d.isVerrouille()).isTrue());
        assertThat(racines).filteredOn(d -> d.getId().equals(b.getId())).singleElement().satisfies(d -> assertThat(d.isVerrouille()).isFalse());
        assertThat(service.getDossierDetail(a.getId(), ed).isVerrouille()).isTrue();
        assertThat(service.getDossierDetail(b.getId(), ed).isVerrouille()).isFalse();
    }
}
