package made.archive.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.HashSet;
import java.util.List;
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

import made.archive.dto.PlanClassementNoeudDto;
import made.archive.dto.PlanClassementNoeudRequestDto;
import made.archive.entite.PlanClassementNoeud;
import made.archive.entite.Retention;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.PlanClassementNoeudRepository;
import made.archive.repository.RoleRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.PlanClassementService;
import made.archive.service.organisation.UniteOrganisationnelleService;

/**
 * Plan de classement : arbre par UO, unicité du code, déplacement sans cycle,
 * suppression refusée si sous-activités ou types rattachés, rattachement d'un
 * type à une activité de SA UO uniquement (et possible même si le type est déjà utilisé).
 */
@Tag("integration")
@SpringBootTest(classes = PlanClassementIntegrationTest.Config.class,
                 webEnvironment = SpringBootTest.WebEnvironment.NONE,
                 properties = {
                     "spring.jpa.hibernate.ddl-auto=create-drop",
                     "spring.flyway.enabled=false",
                     "spring.sql.init.mode=never"
                 })
@Testcontainers
class PlanClassementIntegrationTest
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
        @Bean
        UniteOrganisationnelleService uniteOrganisationnelleService()
        {
            return mock(UniteOrganisationnelleService.class);
        }

        @Bean
        PlanClassementService planClassementService(
            PlanClassementNoeudRepository noeudRepository,
            TypeDocumentRepository typeDocumentRepository,
            made.archive.repository.DocumentRepository documentRepository,
            UniteOrganisationnelleService uoService)
        {
            return new PlanClassementService(noeudRepository, typeDocumentRepository, documentRepository, uoService, mock(AuditLogService.class));
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private PlanClassementService service;
    @Autowired private PlanClassementNoeudRepository noeudRepository;
    @Autowired private TypeDocumentRepository typeDocumentRepository;
    @Autowired private UniteOrganisationnelleRepository uoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UniteOrganisationnelleService uoServiceMock;
    @Autowired private made.archive.repository.DocumentRepository documentRepository;

    private PlanClassementNoeudRequestDto req(Long uoId, Long parentId, String code, String libelle)
    {
        PlanClassementNoeudRequestDto d = new PlanClassementNoeudRequestDto();
        d.setUoId(uoId);
        d.setParentId(parentId);
        d.setCode(code);
        d.setLibelle(libelle);
        return d;
    }

    private User editeur(String email)
    {
        Role role = roleRepository.save(new Role(null, Role_Name.EDITOR));
        User u = new User();
        u.setNom("Nom"); u.setPrenom("Prenom"); u.setEmail(email); u.setPassword("x"); u.setActif(true);
        u.setTelephone("+221" + Math.abs(email.hashCode() % 100000000));
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        u.setRoles(roles);
        return userRepository.save(u);
    }

    private UniteOrganisationnelle uo(String nom)
    {
        UniteOrganisationnelle u = new UniteOrganisationnelle();
        u.setNom(nom);
        return uoRepository.save(u);
    }

    private TypeDocument type(UniteOrganisationnelle uo, User createur, String nom)
    {
        TypeDocument t = new TypeDocument();
        t.setNom(nom);
        t.setRetention(new Retention(null, 5L, 0L, LocalDateTime.now(), made.archive.entite.SortFinal.CONSERVER));
        t.setUniteOrganisationnelle(uo);
        t.setUser(createur);
        return typeDocumentRepository.save(t);
    }

    @Test
    @Transactional
    void arbreCodeUniqueDeplacementEtSuppression()
    {
        UniteOrganisationnelle uo = uo("UO-PC");
        User ed = editeur("pc@test.local");
        when(uoServiceMock.estEditeurDeUO(anyLong(), any())).thenReturn(true);
        when(uoServiceMock.getUOEntiteSiEditeur(anyLong(), any())).thenReturn(uo);

        PlanClassementNoeudDto fin = service.creer(req(uo.getId(), null, "03", "Finances"), ed);
        PlanClassementNoeudDto fact = service.creer(req(uo.getId(), fin.getId(), "03.2", "Factures"), ed);

        // Code unique dans l'UO (insensible à la casse)
        assertThatThrownBy(() -> service.creer(req(uo.getId(), null, "03", "Doublon"), ed))
            .isInstanceOf(BusinessException.class);

        // Sous-arbre : le nœud + ses descendants
        assertThat(service.idsAvecDescendants(fin.getId())).containsExactlyInAnyOrder(fin.getId(), fact.getId());
        assertThat(service.idsAvecDescendants(fact.getId())).containsExactly(fact.getId());

        // Pas de déplacement sous soi-même / un descendant
        assertThatThrownBy(() -> service.deplacer(fin.getId(), fact.getId(), ed))
            .isInstanceOf(BusinessException.class);

        // Arbre reconstruit avec le bon chemin
        List<PlanClassementNoeudDto> arbre = service.getArbre(uo.getId(), ed);
        assertThat(arbre).hasSize(1);
        assertThat(arbre.get(0).getChildren()).extracting(PlanClassementNoeudDto::getCode).containsExactly("03.2");
        PlanClassementNoeud feuille = noeudRepository.findById(fact.getId()).orElseThrow();
        assertThat(PlanClassementService.chemin(feuille)).isEqualTo("03 Finances › 03.2 Factures");

        // Suppression refusée avec une sous-activité
        assertThatThrownBy(() -> service.supprimer(fin.getId(), ed)).isInstanceOf(BusinessException.class);

        // Un type DÉJÀ existant se rattache après coup ; la feuille est alors non supprimable
        TypeDocument t = type(uo, ed, "Facture fournisseur");
        service.rattacherType(t.getId(), fact.getId(), ed);
        assertThat(typeDocumentRepository.findById(t.getId()).orElseThrow().getPlanClassementNoeud().getId())
            .isEqualTo(fact.getId());
        assertThatThrownBy(() -> service.supprimer(fact.getId(), ed)).isInstanceOf(BusinessException.class);

        // Détaché, la feuille puis le parent se suppriment
        service.rattacherType(t.getId(), null, ed);
        service.supprimer(fact.getId(), ed);
        service.supprimer(fin.getId(), ed);
        assertThat(noeudRepository.findByUniteOrganisationnelleId(uo.getId())).isEmpty();
    }

    @Test
    @Transactional
    void unTypeNePeutPasSeRattacherAUneActiviteDUneAutreUo()
    {
        UniteOrganisationnelle uo1 = uo("UO-A");
        UniteOrganisationnelle uo2 = uo("UO-B");
        User ed = editeur("ab@test.local");
        when(uoServiceMock.estEditeurDeUO(anyLong(), any())).thenReturn(true);
        when(uoServiceMock.getUOEntiteSiEditeur(anyLong(), any())).thenAnswer(i ->
            ((Long) i.getArgument(0)).equals(uo1.getId()) ? uo1 : uo2);

        PlanClassementNoeudDto autre = service.creer(req(uo2.getId(), null, "01", "Autre UO"), ed);
        TypeDocument t = type(uo1, ed, "Type UO-A");

        assertThatThrownBy(() -> service.rattacherType(t.getId(), autre.getId(), ed))
            .isInstanceOf(BusinessException.class);
    }

    private made.archive.entite.Document document(UniteOrganisationnelle uo, TypeDocument type, User uploader)
    {
        made.archive.entite.Document d = new made.archive.entite.Document();
        d.setTitre("doc-" + UUID.randomUUID());
        d.setAccess(made.archive.entite.TypeAccess.PUBLIC);
        d.setOriginalSha256(UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64));
        d.setPdfaSha256(UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64));
        d.setStorageKey("pdfa/" + UUID.randomUUID() + ".pdf");
        d.setStatus(made.archive.entite.DocumentStatus.ACTIVE);
        d.setIntegrityLevel(made.archive.entite.IntegrityLevel.STANDARD);
        d.setUniteOrganisationnelle(uo);
        d.setTypeDocument(type);
        d.setUploadedBy(uploader);
        d.setCreateAt(LocalDateTime.now());
        d.setVersion(1L);
        d.setDerniereVersion(true);
        return d;
    }

    /** L'activité effective d'un document = la sienne si elle est précisée, sinon celle de son type — filtre ET export. */
    @Test
    @Transactional
    void leFiltreEtLExportUtilisentLActiviteEffective()
    {
        UniteOrganisationnelle uo = uo("UO-Effective");
        User ed = editeur("effective@test.local");
        when(uoServiceMock.estEditeurDeUO(anyLong(), any())).thenReturn(true);
        when(uoServiceMock.getUOEntiteSiEditeur(anyLong(), any())).thenReturn(uo);

        PlanClassementNoeudDto fin = service.creer(req(uo.getId(), null, "03", "Finances"), ed);
        PlanClassementNoeudDto fact = service.creer(req(uo.getId(), fin.getId(), "03.2", "Factures"), ed);
        PlanClassementNoeudDto achats = service.creer(req(uo.getId(), fin.getId(), "03.4", "Achats ponctuels"), ed);
        PlanClassementNoeudDto rh = service.creer(req(uo.getId(), null, "02", "RH"), ed);

        TypeDocument type = type(uo, ed, "Facture");
        service.rattacherType(type.getId(), fact.getId(), ed);          // activité par défaut : 03.2

        made.archive.entite.Document suitLeType = documentRepository.save(document(uo, type, ed));
        made.archive.entite.Document exception = document(uo, type, ed);
        exception.setPlanClassementNoeud(noeudRepository.findById(achats.getId()).orElseThrow());
        exception = documentRepository.save(exception);
        TypeDocument typeSansActivite = type(uo, ed, "Note");
        made.archive.entite.Document nonClasse = documentRepository.save(document(uo, typeSansActivite, ed));

        java.util.function.Function<PlanClassementNoeudDto, List<UUID>> filtre = noeud ->
            documentRepository.findAll((root, q, cb) ->
                made.archive.service.document.DocumentAccessService.predicatActivite(root, cb,
                    service.idsAvecDescendants(noeud.getId())))
                .stream().map(made.archive.entite.Document::getId).toList();

        assertThat(filtre.apply(fact)).containsExactly(suitLeType.getId());          // 03.2 : celui qui suit son type
        assertThat(filtre.apply(achats)).containsExactly(exception.getId());         // 03.4 : l'exception
        assertThat(filtre.apply(fin)).containsExactlyInAnyOrder(suitLeType.getId(), exception.getId()); // parent : tout dessous
        assertThat(filtre.apply(rh)).isEmpty();                                      // autre branche
        assertThat(filtre.apply(fin)).doesNotContain(nonClasse.getId());             // non classé jamais inclus

        // Export (SEDA) : COALESCE(activité du document, activité du type) — et un document SANS activité reste exporté
        var lignes = documentRepository.findAllByIdPourExport(List.of(suitLeType.getId(), exception.getId(), nonClasse.getId()));
        java.util.Map<UUID, Long> parDoc = new java.util.HashMap<>();
        lignes.forEach(l -> parDoc.put(l.id(), l.planClassementNoeudId()));
        assertThat(lignes).hasSize(3);
        assertThat(parDoc.get(suitLeType.getId())).isEqualTo(fact.getId());
        assertThat(parDoc.get(exception.getId())).isEqualTo(achats.getId());
        assertThat(parDoc.get(nonClasse.getId())).isNull();

        // On ne supprime pas une activité où des documents sont classés à part
        assertThatThrownBy(() -> service.supprimer(achats.getId(), ed)).isInstanceOf(BusinessException.class);
    }
}
