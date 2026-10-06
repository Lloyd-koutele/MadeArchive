package made.archive.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

import made.archive.dto.PhysicalLocationArborescenceRequestDto;
import made.archive.dto.PhysicalLocationNodeDto;
import made.archive.dto.PhysicalLocationTreeNodeDto;
import made.archive.dto.UniteOrganisationnelleDto;
import made.archive.entite.PhysicalLocation;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.DossierRepository;
import made.archive.repository.PhysicalLocationRepository;
import made.archive.repository.RoleRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.PhysicalLocationService;
import made.archive.service.organisation.UniteOrganisationnelleService;

/**
 * Emplacements physiques : un nœud devenu "chemin" ne garde ni capacité ni contrainte (jamais une erreur pour une valeur
 * résiduelle), et une racine de l'UO ne devient jamais point de stockage.
 */
@Tag("integration")
@SpringBootTest(classes = PhysicalLocationIntegrationTest.Config.class,
                 webEnvironment = SpringBootTest.WebEnvironment.NONE,
                 properties = {
                     "spring.jpa.hibernate.ddl-auto=create-drop",
                     "spring.flyway.enabled=false",
                     "spring.sql.init.mode=never"
                 })
@Testcontainers
class PhysicalLocationIntegrationTest
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
        PhysicalLocationService physicalLocationService(
            PhysicalLocationRepository locationRepository, UniteOrganisationnelleRepository uoRepository,
            DocumentRepository documentRepository, UniteOrganisationnelleService uoService,
            TypeDocumentRepository typeDocumentRepository, DossierRepository dossierRepository)
        {
            return new PhysicalLocationService(locationRepository, uoRepository, documentRepository, uoService,
                mock(AuditLogService.class), typeDocumentRepository, dossierRepository);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private PhysicalLocationService service;
    @Autowired private PhysicalLocationRepository locationRepository;
    @Autowired private UniteOrganisationnelleRepository uoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UniteOrganisationnelleService uoServiceMock;

    private User editeur(UniteOrganisationnelle uo)
    {
        Role role = roleRepository.save(new Role(null, Role_Name.EDITOR));
        User u = new User();
        u.setNom("Nom"); u.setPrenom("Prenom"); u.setEmail("emp@test.local"); u.setPassword("x"); u.setActif(true);
        u.setTelephone("+221700000042");
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        u.setRoles(roles);
        u = userRepository.save(u);
        UniteOrganisationnelleDto dto = new UniteOrganisationnelleDto();
        dto.setId(uo.getId());
        when(uoServiceMock.getUOActuelleUser(any())).thenReturn(Optional.of(dto));
        return u;
    }

    private PhysicalLocationTreeNodeDto noeud(String nom, boolean stockage, Integer capacite, PhysicalLocationTreeNodeDto... enfants)
    {
        PhysicalLocationTreeNodeDto n = new PhysicalLocationTreeNodeDto();
        n.setName(nom);
        n.setStoragePoint(stockage);
        n.setCapaciteMax(capacite);
        n.setModeContrainte("LIBRE");
        n.setChildren(new java.util.ArrayList<>(List.of(enfants)));
        return n;
    }

    private PhysicalLocationNodeDto creer(UniteOrganisationnelle uo, User ed, PhysicalLocationTreeNodeDto racine)
    {
        PhysicalLocationArborescenceRequestDto r = new PhysicalLocationArborescenceRequestDto();
        r.setUniteOrganisationnelleId(uo.getId());
        r.setNode(racine);
        return service.creerArborescence(r, ed);
    }

    @Test
    @Transactional
    void unNoeudCheminAvecUneCapaciteResiduelleNEstPasUneErreur_laCapaciteEstIgnoree()
    {
        UniteOrganisationnelle uo = new UniteOrganisationnelle();
        uo.setNom("UO-Emplacements");
        uo = uoRepository.save(uo);
        User ed = editeur(uo);

        // Scénario : "Cartons A" créé en point de stockage avec une capacité, puis repassé en chemin avant "Créer"
        PhysicalLocationNodeDto racine = creer(uo, ed, noeud("Batiment B", false, null,
            noeud("Cartons A", false, 50)));

        assertThat(racine.getChildren()).hasSize(1);
        PhysicalLocation cartons = locationRepository.findById(racine.getChildren().get(0).getId()).orElseThrow();
        assertThat(cartons.isStoragePoint()).isFalse();
        assertThat(cartons.getCapaciteMax()).isNull();      // la capacité résiduelle a disparu
    }

    @Test
    @Transactional
    void repasserUnPointDeStockageEnCheminEfface_capaciteEtContrainte()
    {
        UniteOrganisationnelle uo = new UniteOrganisationnelle();
        uo.setNom("UO-Conversion");
        uo = uoRepository.save(uo);
        User ed = editeur(uo);

        PhysicalLocationNodeDto racine = creer(uo, ed, noeud("Batiment", false, null,
            noeud("Boite", true, 10)));
        var boiteId = racine.getChildren().get(0).getId();
        assertThat(locationRepository.findById(boiteId).orElseThrow().getCapaciteMax()).isEqualTo(10);

        service.changerTypeStockage(boiteId, false, ed);

        PhysicalLocation boite = locationRepository.findById(boiteId).orElseThrow();
        assertThat(boite.isStoragePoint()).isFalse();
        assertThat(boite.getCapaciteMax()).isNull();
        assertThat(boite.getModeContrainte()).isEqualTo(made.archive.entite.LocationModeContrainte.LIBRE);

        // Un nœud qui n'est pas une racine peut redevenir un point de stockage...
        assertThat(service.changerTypeStockage(boiteId, true, ed).isStoragePoint()).isTrue();
    }

    @Test
    @Transactional
    void uneRacineDeLUONeDevientJamaisUnPointDeStockage()
    {
        UniteOrganisationnelle uo = new UniteOrganisationnelle();
        uo.setNom("UO-Racine");
        uo = uoRepository.save(uo);
        User ed = editeur(uo);

        PhysicalLocationNodeDto racine = creer(uo, ed, noeud("Batiment A", false, null));

        assertThatThrownBy(() -> service.changerTypeStockage(racine.getId(), true, ed))
            .isInstanceOf(BusinessException.class).hasMessageContaining("racine");
    }
}
