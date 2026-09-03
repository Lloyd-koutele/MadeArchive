package made.archive.service.document;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import made.archive.config.DocumentExportProperties;
import made.archive.dto.ExportApercuDocumentDto;
import made.archive.dto.ExportApercuRequestDto;
import made.archive.dto.ExportLancerRequestDto;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.GroupeAccess;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeAccess;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.ExportJobRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.notification.NotificationService;
import made.archive.service.organisation.UniteOrganisationnelleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Vérifie la frontière de sécurité à deux niveaux de l'export administratif
 * (voir 5.9.2 du mémoire) : un export "normal" ne doit JAMAIS montrer plus
 * qu'un accès direct au document ne montrerait déjà ; l'élévation vers les
 * documents privés non-membres est réservée à ROLE_ADMIN et exige un motif.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class DocumentExportServiceTest
{
    @Mock private DocumentRepository              documentRepository;
    @Mock private ExportJobRepository             exportJobRepository;
    @Mock private UniteOrganisationnelleService   uniteOrganisationnelleService;
    @Mock private NotificationService             notificationService;
    @Mock private AuditLogService                 auditLogService;
    @Mock private DocumentExportGenerationService generationService;

    private final DocumentExportProperties properties = new DocumentExportProperties();

    @InjectMocks
    private DocumentExportService service;

    private User utilisateur(Role_Name role)
    {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("test@madearchive.local");
        Set<Role> roles = new HashSet<>();
        roles.add(new Role(null, role));
        u.setRoles(roles);
        return u;
    }

    private UniteOrganisationnelle uo(Long id)
    {
        UniteOrganisationnelle uo = new UniteOrganisationnelle();
        uo.setId(id);
        uo.setNom("UO-" + id);
        return uo;
    }

    private Document document(TypeAccess access, UniteOrganisationnelle uo, List<User> membres)
    {
        Document d = new Document();
        d.setId(UUID.randomUUID());
        d.setTitre("Document de test");
        d.setAccess(access);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUniteOrganisationnelle(uo);
        if (membres != null)
        {
            GroupeAccess groupe = new GroupeAccess();
            groupe.setMembres(membres);
            d.setGroupe(groupe);
        }
        return d;
    }

    @Test
    void unAdminUoNePeutPasDemanderUneUoHorsDeSonAutorite()
    {
        User adminUo = utilisateur(Role_Name.ADMIN_UO);
        when(uniteOrganisationnelleService.getUoIdsSousAutorite(adminUo)).thenReturn(Set.of(1L));

        ExportApercuRequestDto requete = new ExportApercuRequestDto();
        requete.setUoIds(List.of(99L)); // hors de son autorité (seule 1L l'est)

        assertThatThrownBy(() -> service.apercu(requete, adminUo))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("autorité");
    }

    @Test
    void seulUnAdminGlobalPeutDemanderLElevationVersLesDocumentsPrivesNonMembres()
    {
        User adminUo = utilisateur(Role_Name.ADMIN_UO);
        lenient().when(uniteOrganisationnelleService.getUoIdsSousAutorite(adminUo)).thenReturn(Set.of(1L));

        ExportApercuRequestDto requete = new ExportApercuRequestDto();
        requete.setUoIds(List.of(1L));
        requete.setIncludePriveNonMembre(true);

        assertThatThrownBy(() -> service.apercu(requete, adminUo))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("administrateur global");
    }

    @Test
    void lApercuNormalNeMontreQueLesDocumentsPublicsEtLesPrivesDontLAppelantEstMembre()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        when(uniteOrganisationnelleService.getUoIdsSousAutorite(admin)).thenReturn(null); // ADMIN global

        UniteOrganisationnelle uneUo = uo(1L);
        Document docPublic       = document(TypeAccess.PUBLIC, uneUo, null);
        Document docPriveMembre  = document(TypeAccess.PRIVE, uneUo, List.of(admin));
        Document docPriveEtranger = document(TypeAccess.PRIVE, uneUo, List.of(utilisateur(Role_Name.EDITOR)));

        when(documentRepository.findForExport(any(), anyCollection()))
            .thenReturn(List.of(docPublic, docPriveMembre, docPriveEtranger));

        ExportApercuRequestDto requete = new ExportApercuRequestDto();
        requete.setUoIds(List.of(1L));

        List<ExportApercuDocumentDto> resultat = service.apercu(requete, admin);

        assertThat(resultat)
            .extracting(ExportApercuDocumentDto::getId)
            .containsExactlyInAnyOrder(docPublic.getId(), docPriveMembre.getId());
    }

    @Test
    void lApercuElargiMontreAussiLesDocumentsPrivesNonMembresMaisLesSignaleCommeTels()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        when(uniteOrganisationnelleService.getUoIdsSousAutorite(admin)).thenReturn(null);

        UniteOrganisationnelle uneUo = uo(1L);
        Document docPriveEtranger = document(TypeAccess.PRIVE, uneUo, List.of(utilisateur(Role_Name.EDITOR)));

        when(documentRepository.findForExport(any(), anyCollection()))
            .thenReturn(List.of(docPriveEtranger));

        ExportApercuRequestDto requete = new ExportApercuRequestDto();
        requete.setUoIds(List.of(1L));
        requete.setIncludePriveNonMembre(true);

        List<ExportApercuDocumentDto> resultat = service.apercu(requete, admin);

        assertThat(resultat).hasSize(1);
        assertThat(resultat.get(0).isAccesNormal())
            .as("un document privé non-membre inclus par élévation doit être signalé comme tel")
            .isFalse();
    }

    @Test
    void lancerExportAvecElevationSansMotifEstRefuse()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        lenient().when(uniteOrganisationnelleService.getUoIdsSousAutorite(admin)).thenReturn(null);

        ExportLancerRequestDto requete = new ExportLancerRequestDto();
        requete.setUoIds(List.of(1L));
        requete.setIncludePriveNonMembre(true);
        requete.setMotif("  "); // vide en pratique

        assertThatThrownBy(() -> service.lancerExport(requete, admin))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("motif");
    }

    @Test
    void lancerExportSansDocumentDansLePerimetreEstRefuse()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        when(uniteOrganisationnelleService.getUoIdsSousAutorite(admin)).thenReturn(null);
        when(documentRepository.findForExport(any(), anyCollection())).thenReturn(List.of());

        ExportLancerRequestDto requete = new ExportLancerRequestDto();
        requete.setUoIds(List.of(1L));

        assertThatThrownBy(() -> service.lancerExport(requete, admin))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Aucun document");
    }
}
