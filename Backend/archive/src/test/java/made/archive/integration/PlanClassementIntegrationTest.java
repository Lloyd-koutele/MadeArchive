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

    private PlanClassementNoeudRequestDto req(Long uoId, Long parentId, String libelle)
    {
        PlanClassementNoeudRequestDto d = new PlanClassementNoeudRequestDto();
        d.setUoId(uoId);
        d.setParentId(parentId);
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
    void lesCodesSontGeneresSelonLaPositionDansLArbre()
    {
        UniteOrganisationnelle uo = uo("UO-Codes");
        User ed = editeur("codes@test.local");
        when(uoServiceMock.estEditeurDeUO(anyLong(), any())).thenReturn(true);
        when(uoServiceMock.getUOEntiteSiEditeur(anyLong(), any())).thenReturn(uo);

        PlanClassementNoeudDto a = service.creer(req(uo.getId(), null, "Enseignement"), ed);
        PlanClassementNoeudDto b = service.creer(req(uo.getId(), null, "Finances"), ed);
        PlanClassementNoeudDto c = service.creer(req(uo.getId(), null, "RH"), ed);
        PlanClassementNoeudDto a1 = service.creer(req(uo.getId(), a.getId(), "Examens"), ed);
        PlanClassementNoeudDto a2 = service.creer(req(uo.getId(), a.getId(), "Cours"), ed);
        PlanClassementNoeudDto a11 = service.creer(req(uo.getId(), a1.getId(), "Sujets"), ed);
        PlanClassementNoeudDto a12 = service.creer(req(uo.getId(), a1.getId(), "Corrigés"), ed);
        PlanClassementNoeudDto b1 = service.creer(req(uo.getId(), b.getId(), "Budget"), ed);

        assertThat(List.of(a, b, c)).extracting(PlanClassementNoeudDto::getCode).containsExactly("01", "02", "03");
        assertThat(List.of(a1, a2)).extracting(PlanClassementNoeudDto::getCode).containsExactly("01.1", "01.2");
        assertThat(List.of(a11, a12)).extracting(PlanClassementNoeudDto::getCode).containsExactly("01.1.1", "01.1.2");
        assertThat(b1.getCode()).isEqualTo("02.1");

        // Le code saisi par le client n'existe plus : seul le libellé compte, jamais de doublon possible
        assertThatThrownBy(() -> service.creer(req(uo.getId(), null, "  "), ed)).isInstanceOf(BusinessException.class);
    }

    @Test
    @Transactional
    void lOrdreEstNumeriqueEtUnRangSupprimeEnDernierEstReattribue()
    {
        UniteOrganisationnelle uo = uo("UO-Ordre");
        User ed = editeur("ordre@test.local");
        when(uoServiceMock.estEditeurDeUO(anyLong(), any())).thenReturn(true);
        when(uoServiceMock.getUOEntiteSiEditeur(anyLong(), any())).thenReturn(uo);

        PlanClassementNoeudDto racine = service.creer(req(uo.getId(), null, "Racine"), ed);
        PlanClassementNoeudDto dernier = null;
        for (int i = 1; i <= 11; i++)
        {
            dernier = service.creer(req(uo.getId(), racine.getId(), "Enfant " + i), ed);
        }
        assertThat(dernier.getCode()).isEqualTo("01.11");

        // 01.10 se range après 01.9, pas après 01.1
        List<String> codes = service.getArbre(uo.getId(), ed).get(0).getChildren().stream()
            .map(PlanClassementNoeudDto::getCode).toList();
        assertThat(codes).containsExactly("01.1", "01.2", "01.3", "01.4", "01.5", "01.6", "01.7", "01.8", "01.9", "01.10", "01.11");

        // Supprimer un rang du milieu ne renumérote rien ; supprimer le dernier libère son numéro
        PlanClassementNoeud milieu = noeudRepository.findByUniteOrganisationnelleId(uo.getId()).stream()
            .filter(n -> n.getCode().equals("01.4")).findFirst().orElseThrow();
        service.supprimer(milieu.getId(), ed);
        service.supprimer(dernier.getId(), ed);
        assertThat(service.creer(req(uo.getId(), racine.getId(), "Nouveau"), ed).getCode()).isEqualTo("01.11");
    }

    @Test
    @Transactional
    void suppressionEnCascadeSansDocument_etDetachementDesTypes()
    {
        UniteOrganisationnelle uo = uo("UO-Cascade");
        User ed = editeur("cascade@test.local");
        when(uoServiceMock.estEditeurDeUO(anyLong(), any())).thenReturn(true);
        when(uoServiceMock.getUOEntiteSiEditeur(anyLong(), any())).thenReturn(uo);

        PlanClassementNoeudDto fin = service.creer(req(uo.getId(), null, "Finances"), ed);
        PlanClassementNoeudDto fact = service.creer(req(uo.getId(), fin.getId(), "Factures"), ed);
        PlanClassementNoeudDto fournisseurs = service.creer(req(uo.getId(), fact.getId(), "Fournisseurs"), ed);
        PlanClassementNoeudDto autre = service.creer(req(uo.getId(), null, "RH"), ed);

        // Un type sans aucun document peut y être rattaché : il sera simplement détaché
        TypeDocument t = type(uo, ed, "Facture fournisseur");
        service.rattacherType(t.getId(), fournisseurs.getId(), ed);

        assertThat(service.idsAvecDescendants(fin.getId()))
            .containsExactlyInAnyOrder(fin.getId(), fact.getId(), fournisseurs.getId());

        // Le parent se supprime d'un coup avec toutes ses sous-activités
        service.supprimer(fin.getId(), ed);

        assertThat(noeudRepository.findByUniteOrganisationnelleId(uo.getId())).extracting(PlanClassementNoeud::getId)
            .containsExactly(autre.getId());
        assertThat(typeDocumentRepository.findById(t.getId()).orElseThrow().getPlanClassementNoeud()).isNull();
    }

    @Test
    @Transactional
    void desDocumentsClassesVerrouillentL_activiteSesParentsEtSaSuppression()
    {
        UniteOrganisationnelle uo = uo("UO-Verrou");
        User ed = editeur("verrou@test.local");
        when(uoServiceMock.estEditeurDeUO(anyLong(), any())).thenReturn(true);
        when(uoServiceMock.getUOEntiteSiEditeur(anyLong(), any())).thenReturn(uo);

        PlanClassementNoeudDto fin = service.creer(req(uo.getId(), null, "Finances"), ed);
        PlanClassementNoeudDto fact = service.creer(req(uo.getId(), fin.getId(), "Factures"), ed);
        PlanClassementNoeudDto libre = service.creer(req(uo.getId(), fin.getId(), "Budget"), ed);
        PlanClassementNoeudDto autre = service.creer(req(uo.getId(), null, "RH"), ed);

        // Le document HÉRITE de l'activité de son type : c'est lui qui verrouille 01.1
        TypeDocument type = type(uo, ed, "Facture");
        service.rattacherType(type.getId(), fact.getId(), ed);
        documentRepository.save(document(uo, type, ed));

        PlanClassementNoeudDto fin2 = service.getArbre(uo.getId(), ed).stream()
            .filter(n -> n.getId().equals(fin.getId())).findFirst().orElseThrow();
        assertThat(fin2.isVerrouille()).isTrue();                                   // parent d'une activité verrouillée
        assertThat(fin2.getChildren()).filteredOn(n -> n.getId().equals(fact.getId()))
            .singleElement().satisfies(n -> { assertThat(n.isVerrouille()).isTrue(); assertThat(n.getNbDocuments()).isEqualTo(1); });
        assertThat(fin2.getChildren()).filteredOn(n -> n.getId().equals(libre.getId()))
            .singleElement().satisfies(n -> assertThat(n.isVerrouille()).isFalse()); // sœur sans document : libre

        PlanClassementNoeudRequestDto r = req(uo.getId(), null, "Nouveau nom");
        assertThatThrownBy(() -> service.modifier(fact.getId(), r, ed)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.modifier(fin.getId(), r, ed)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.deplacer(fact.getId(), autre.getId(), ed)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.deplacer(fin.getId(), null, ed)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.supprimer(fact.getId(), ed)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.supprimer(fin.getId(), ed)).isInstanceOf(BusinessException.class); // sous-arbre verrouillé

        // On peut encore AJOUTER une sous-activité sous une activité verrouillée, et agir sur la branche libre
        assertThat(service.creer(req(uo.getId(), fact.getId(), "Fournisseurs"), ed).getCode()).isEqualTo("01.1.1");
        assertThat(service.modifier(libre.getId(), req(uo.getId(), null, "Budget annuel"), ed).getLibelle())
            .isEqualTo("Budget annuel");
        service.supprimer(libre.getId(), ed);
    }

    private made.archive.dto.PlanClassementArbreRequestDto.Noeud draft(Long id, String libelle,
        made.archive.dto.PlanClassementArbreRequestDto.Noeud... enfants)
    {
        var n = new made.archive.dto.PlanClassementArbreRequestDto.Noeud();
        n.setId(id);
        n.setLibelle(libelle);
        n.setChildren(new java.util.ArrayList<>(List.of(enfants)));
        return n;
    }

    @Test
    @Transactional
    void creerUneArborescenceEnUnAppel_codesGeneresEtAtomique()
    {
        UniteOrganisationnelle uo = uo("UO-Arbre");
        User ed = editeur("arbre@test.local");
        when(uoServiceMock.estEditeurDeUO(anyLong(), any())).thenReturn(true);
        when(uoServiceMock.getUOEntiteSiEditeur(anyLong(), any())).thenReturn(uo);

        var requete = new made.archive.dto.PlanClassementArbreRequestDto();
        requete.setUoId(uo.getId());
        requete.setNode(draft(null, "Enseignement",
            draft(null, "Examens", draft(null, "Sujets"), draft(null, "Corrigés")),
            draft(null, "Cours")));

        PlanClassementNoeudDto racine = service.creerArborescence(requete, ed);

        assertThat(racine.getCode()).isEqualTo("01");
        assertThat(racine.getChildren()).extracting(PlanClassementNoeudDto::getCode).containsExactly("01.1", "01.2");
        assertThat(racine.getChildren().get(0).getChildren()).extracting(PlanClassementNoeudDto::getCode)
            .containsExactly("01.1.1", "01.1.2");

        // Sous une activité existante : la racine du brouillon reçoit le rang suivant
        var sous = new made.archive.dto.PlanClassementArbreRequestDto();
        sous.setUoId(uo.getId());
        sous.setParentId(racine.getId());
        sous.setNode(draft(null, "Soutenances"));
        assertThat(service.creerArborescence(sous, ed).getCode()).isEqualTo("01.3");

        // Un libellé vide quelque part dans le brouillon est refusé (en production, la transaction de l'appel
        // annule alors toute l'arborescence : rien n'est créé à moitié)
        var invalide = new made.archive.dto.PlanClassementArbreRequestDto();
        invalide.setUoId(uo.getId());
        invalide.setNode(draft(null, "Finances", draft(null, "  ")));
        assertThatThrownBy(() -> service.creerArborescence(invalide, ed)).isInstanceOf(BusinessException.class);
    }

    @Test
    @Transactional
    void mettreAJourUneArborescence_renommeAjouteEtRespecteLeVerrou()
    {
        UniteOrganisationnelle uo = uo("UO-MajArbre");
        User ed = editeur("majarbre@test.local");
        when(uoServiceMock.estEditeurDeUO(anyLong(), any())).thenReturn(true);
        when(uoServiceMock.getUOEntiteSiEditeur(anyLong(), any())).thenReturn(uo);

        PlanClassementNoeudDto fin = service.creer(req(uo.getId(), null, "Finances"), ed);
        PlanClassementNoeudDto fact = service.creer(req(uo.getId(), fin.getId(), "Factures"), ed);
        PlanClassementNoeudDto budget = service.creer(req(uo.getId(), fin.getId(), "Budget"), ed);

        TypeDocument type = type(uo, ed, "Facture");
        service.rattacherType(type.getId(), fact.getId(), ed);
        documentRepository.save(document(uo, type, ed));            // verrouille 01 et 01.1

        // Renommer l'activité LIBRE et ajouter des sous-activités, sans toucher aux verrouillées
        PlanClassementNoeudDto maj = service.mettreAJourArborescence(fin.getId(),
            draft(fin.getId(), "Finances",
                draft(fact.getId(), "Factures", draft(null, "Fournisseurs")),
                draft(budget.getId(), "Budget annuel", draft(null, "Prévisions"))), ed);

        assertThat(maj.getChildren()).extracting(PlanClassementNoeudDto::getLibelle)
            .containsExactly("Factures", "Budget annuel");
        assertThat(maj.getChildren().get(0).getChildren()).extracting(PlanClassementNoeudDto::getCode).containsExactly("01.1.1");
        assertThat(maj.getChildren().get(1).getChildren()).extracting(PlanClassementNoeudDto::getCode).containsExactly("01.2.1");

        // Renommer une activité verrouillée est refusé (même depuis l'organigramme)
        assertThatThrownBy(() -> service.mettreAJourArborescence(fin.getId(),
            draft(fin.getId(), "Finances", draft(fact.getId(), "Factures clients")), ed))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.mettreAJourArborescence(fin.getId(),
            draft(fin.getId(), "Direction financière"), ed)).isInstanceOf(BusinessException.class);

        // Un nœud d'une autre arborescence est refusé
        PlanClassementNoeudDto autre = service.creer(req(uo.getId(), null, "RH"), ed);
        assertThatThrownBy(() -> service.mettreAJourArborescence(fin.getId(),
            draft(fin.getId(), "Finances", draft(autre.getId(), "RH")), ed)).isInstanceOf(BusinessException.class);
    }

    @Test
    @Transactional
    void deplacerRecalculeLeCodeDeLActiviteEtDeSesDescendants()
    {
        UniteOrganisationnelle uo = uo("UO-Deplacement");
        User ed = editeur("deplacement@test.local");
        when(uoServiceMock.estEditeurDeUO(anyLong(), any())).thenReturn(true);
        when(uoServiceMock.getUOEntiteSiEditeur(anyLong(), any())).thenReturn(uo);

        PlanClassementNoeudDto a = service.creer(req(uo.getId(), null, "A"), ed);
        service.creer(req(uo.getId(), a.getId(), "A-1"), ed);
        PlanClassementNoeudDto b = service.creer(req(uo.getId(), null, "B"), ed);
        PlanClassementNoeudDto b1 = service.creer(req(uo.getId(), b.getId(), "B-1"), ed);
        service.creer(req(uo.getId(), b1.getId(), "B-1-1"), ed);

        // Pas de déplacement sous soi-même / un descendant
        assertThatThrownBy(() -> service.deplacer(b.getId(), b1.getId(), ed)).isInstanceOf(BusinessException.class);

        // B (02) devient enfant de A (01) : 01.2, et ses descendants suivent (01.2.1, 01.2.1.1)
        assertThat(service.deplacer(b.getId(), a.getId(), ed).getCode()).isEqualTo("01.2");
        assertThat(noeudRepository.findByUniteOrganisationnelleId(uo.getId())).extracting(PlanClassementNoeud::getCode)
            .containsExactlyInAnyOrder("01", "01.1", "01.2", "01.2.1", "01.2.1.1");

        // Retour à la racine : prochain numéro libre
        assertThat(service.deplacer(b.getId(), null, ed).getCode()).isEqualTo("02");
        assertThat(noeudRepository.findByUniteOrganisationnelleId(uo.getId())).extracting(PlanClassementNoeud::getCode)
            .containsExactlyInAnyOrder("01", "01.1", "02", "02.1", "02.1.1");
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

        PlanClassementNoeudDto autre = service.creer(req(uo2.getId(), null, "Autre UO"), ed);
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

        PlanClassementNoeudDto fin = service.creer(req(uo.getId(), null, "Finances"), ed);
        PlanClassementNoeudDto fact = service.creer(req(uo.getId(), fin.getId(), "Factures"), ed);
        PlanClassementNoeudDto achats = service.creer(req(uo.getId(), fin.getId(), "Achats ponctuels"), ed);
        PlanClassementNoeudDto rh = service.creer(req(uo.getId(), null, "RH"), ed);

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
