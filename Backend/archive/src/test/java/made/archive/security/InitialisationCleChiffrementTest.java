package made.archive.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import made.archive.entite.Document;
import made.archive.repository.DocumentRepository;
import made.archive.security.ControleCleChiffrementService.Etat;
import made.archive.service.integrite.AlerteIntegriteService;
import made.archive.service.storage.StorageService;

@Tag("unit")
class InitialisationCleChiffrementTest
{
    private ControleCleChiffrementService controle;
    private DocumentEncryptionService chiffrement;
    private DocumentRepository documents;
    private StorageService stockage;
    private AlerteIntegriteService alertes;
    private InitialisationCleChiffrement runner;

    @BeforeEach
    void setUp()
    {
        controle = mock(ControleCleChiffrementService.class);
        chiffrement = mock(DocumentEncryptionService.class);
        documents = mock(DocumentRepository.class);
        stockage = mock(StorageService.class);
        alertes = mock(AlerteIntegriteService.class);
        runner = new InitialisationCleChiffrement(controle, chiffrement, documents, stockage, alertes);
    }

    private void unDocumentArchive()
    {
        Document d = new Document();
        d.setStorageKey("pdfa/ancien.pdf");
        when(documents.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(d)));
        when(stockage.download("pdfa/ancien.pdf")).thenReturn(new ByteArrayInputStream(new byte[] { 1, 2 }));
    }

    @Test
    void baseVide_enregistreLaReference() throws Exception
    {
        when(controle.etat()).thenReturn(Etat.REFERENCE_NON_INITIALISEE);
        when(documents.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        runner.run(new DefaultApplicationArguments());

        verify(controle).enregistrerReference();
    }

    @Test
    void archivesExistantes_enregistreSeulementSiLaCleOuvreLaPlusAncienne() throws Exception
    {
        when(controle.etat()).thenReturn(Etat.REFERENCE_NON_INITIALISEE);
        unDocumentArchive();
        when(chiffrement.peutDechiffrer(any())).thenReturn(true);

        runner.run(new DefaultApplicationArguments());

        verify(controle).enregistrerReference();
    }

    @Test
    void archivesExistantes_cleQuiNOuvrePasRien_neFigeJamaisUneMauvaiseReference() throws Exception
    {
        when(controle.etat()).thenReturn(Etat.REFERENCE_NON_INITIALISEE);
        unDocumentArchive();
        when(chiffrement.peutDechiffrer(any())).thenReturn(false);

        runner.run(new DefaultApplicationArguments());

        verify(controle, never()).enregistrerReference();
        verify(alertes).cleChiffrementAnormale(anyString());
    }

    @Test
    void cleDifferente_alerteSansRienEnregistrer() throws Exception
    {
        when(controle.etat()).thenReturn(Etat.CLE_DIFFERENTE);

        runner.run(new DefaultApplicationArguments());

        verify(alertes).cleChiffrementAnormale(anyString());
        verify(controle, never()).enregistrerReference();
    }

    @Test
    void stockageIndisponible_reporteSansBloquerLeDemarrage() throws Exception
    {
        when(controle.etat()).thenReturn(Etat.REFERENCE_NON_INITIALISEE);
        unDocumentArchive();
        when(stockage.download(anyString())).thenThrow(new RuntimeException("MinIO injoignable"));

        runner.run(new DefaultApplicationArguments()); // ne lève pas

        verify(controle, never()).enregistrerReference();
    }
}
