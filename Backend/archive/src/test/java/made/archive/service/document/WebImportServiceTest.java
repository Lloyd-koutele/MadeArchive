package made.archive.service.document;

import java.net.URI;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import made.archive.config.WebImportHttpProperties;
import made.archive.dto.WebImportPreviewRequestDto;
import made.archive.entite.AuditAction;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.service.audit.AuditLogService;
import made.archive.service.importweb.ClientHttpSecurise;
import made.archive.service.importweb.LienRefuseException;
import made.archive.service.importweb.LimiteurImports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Vérifie la classification par motif d'URL (voir 5.3.2 du mémoire) —
 * reecrireLienGoogleSiApplicable et estDossierDrive sont des méthodes PURES
 * (aucun appel réseau), rendues package-private spécifiquement pour ce test.
 * Les trois bugs réels documentés en 5.12 (Défis techniques) sont tous nés
 * dans des motifs de cette famille — c'est le candidat le plus justifié du
 * projet pour un test unitaire dédié.
 */
@Tag("unit")
class WebImportServiceTest
{
    // permis (Semaphore) n'est initialisé que par @PostConstruct — non
    // appelé ici (pas de contexte Spring), mais inutile pour les méthodes
    // testées : aucune des deux n'y touche.
    private final AuditLogService audit = mock(AuditLogService.class);
    private final LimiteurImports limiteur = mock(LimiteurImports.class);
    private final WebImportService service = new WebImportService(
        mock(HeadlessBrowserImportService.class),
        mock(ClientHttpSecurise.class),
        new WebImportHttpProperties(),
        limiteur,
        audit);

    @Test
    void reconnaitUnDossierGoogleDrive()
    {
        boolean resultat = service.estDossierDrive(
            "https://drive.google.com/drive/folders/1a2B3c4D5e");

        assertThat(resultat).isTrue();
    }

    @Test
    void reconnaitUnDossierGoogleDriveAvecIndexUtilisateur()
    {
        boolean resultat = service.estDossierDrive(
            "https://drive.google.com/drive/u/0/folders/1a2B3c4D5e");

        assertThat(resultat).isTrue();
    }

    @Test
    void neReconnaitPasUnFichierDriveUniqueCommeUnDossier()
    {
        boolean resultat = service.estDossierDrive(
            "https://drive.google.com/file/d/1a2B3c4D5e/view");

        assertThat(resultat).isFalse();
    }

    @Test
    void reecritUnDocumentGoogleDocsVersSonExportPdf()
    {
        URI reecrit = service.reecrireLienGoogleSiApplicable(
            URI.create("https://docs.google.com/document/d/abc123/edit"));

        assertThat(reecrit.toString())
            .isEqualTo("https://docs.google.com/document/d/abc123/export?format=pdf");
    }

    @Test
    void reecritUnePresentationGoogleSlidesVersSonExportPdf()
    {
        URI reecrit = service.reecrireLienGoogleSiApplicable(
            URI.create("https://docs.google.com/presentation/d/xyz789/edit"));

        assertThat(reecrit.toString())
            .isEqualTo("https://docs.google.com/presentation/d/xyz789/export/pdf");
    }

    @Test
    void reecritUnFichierDriveUniqueVersSonTelechargementDirect()
    {
        URI reecrit = service.reecrireLienGoogleSiApplicable(
            URI.create("https://drive.google.com/file/d/1a2B3c4D5e/view?usp=sharing"));

        assertThat(reecrit.toString())
            .isEqualTo("https://drive.google.com/uc?export=download&id=1a2B3c4D5e");
    }

    @ParameterizedTest
    @CsvSource({
        "https://example.com/rapport.pdf",
        "https://example.com/dossier/index.html",
        "https://drive.google.com/drive/folders/1a2B3c4D5e"
    })
    void neReecritPasUneUrlNonGoogleDocsOuDrive(String url)
    {
        URI original = URI.create(url);

        URI resultat = service.reecrireLienGoogleSiApplicable(original);

        assertThat(resultat).isEqualTo(original);
    }

    // ── sécurité de l'import par lien ────────────────────────────────────────────────────────────────────

    private static WebImportPreviewRequestDto lien(String url)
    {
        WebImportPreviewRequestDto r = new WebImportPreviewRequestDto();
        r.setUrl(url);
        return r;
    }

    private static User editeur()
    {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        return u;
    }

    @ParameterizedTest
    @CsvSource({
        "http://127.0.0.1:9000/documents/x",
        "http://localhost:9000/",
        "http://169.254.169.254/latest/meta-data/",
        "http://10.0.0.5/secret",
        "http://192.168.1.10/",
        "http://172.18.0.4:9000/",
        "http://100.64.0.1/",
        "http://2130706433/",
        "http://[::1]:7700/",
        "http://[fd00::1]/",
        "http://[::ffff:127.0.0.1]/"
    })
    void unLienVersLeReseauInterneEstRefuse_etTraceDansLeJournal(String url)
    {
        User acteur = editeur();

        assertThatThrownBy(() -> service.previewer(lien(url), acteur))
            .isInstanceOf(LienRefuseException.class).hasMessageContaining("réseau interne");

        verify(audit).log(eq(acteur), eq(AuditAction.IMPORT_LIEN_REFUSE),
            org.mockito.ArgumentMatchers.argThat((String d) -> d.contains("Lien d'import refusé")), eq(false));
    }

    @ParameterizedTest
    @CsvSource({ "file:///etc/passwd", "ftp://example.com/x.pdf", "gopher://example.com/", "javascript:alert(1)" })
    void unSchemaAutreQueHttpEstRefuse(String url)
    {
        assertThatThrownBy(() -> service.previewer(lien(url), editeur())).isInstanceOf(LienRefuseException.class);
    }

    @Test
    void lePlafondParUtilisateurBloqueAvantTouteRequete()
    {
        User acteur = editeur();
        doThrow(new BusinessException("Trop d'imports par lien")).when(limiteur).consommer(eq(acteur.getId()), anyInt(), anyInt());

        assertThatThrownBy(() -> service.previewer(lien("http://127.0.0.1/"), acteur))
            .isInstanceOf(BusinessException.class).hasMessageContaining("Trop d'imports");

        // refusé pour le plafond, pas pour l'adresse : aucune trace de "lien refusé" ni d'accès réseau
        verify(audit, never()).log(org.mockito.ArgumentMatchers.any(), eq(AuditAction.IMPORT_LIEN_REFUSE), anyString(), eq(false));
    }
}
