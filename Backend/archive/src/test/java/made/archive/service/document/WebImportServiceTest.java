package made.archive.service.document;

import java.net.URI;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import made.archive.config.WebImportHttpProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
    private final WebImportService service = new WebImportService(
        mock(HeadlessBrowserImportService.class),
        mock(WebImportFolderCache.class),
        new WebImportHttpProperties());

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
}
