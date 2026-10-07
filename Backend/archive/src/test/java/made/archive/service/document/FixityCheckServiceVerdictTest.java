package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import made.archive.entite.CheckResult;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.FixityCheckResult;
import made.archive.repository.DocumentRepository;
import made.archive.repository.FixityCheckResultRepository;
import made.archive.repository.UserRepository;
import made.archive.security.DocumentEncryptionService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.integrite.AlerteIntegriteService;
import made.archive.service.integrite.PreuveIntegriteService;
import made.archive.service.integrite.PreuveIntegriteService.Constat;
import made.archive.service.integrite.PreuveIntegriteService.Verdict;
import made.archive.service.notification.NotificationService;
import made.archive.service.organisation.UniteOrganisationnelleService;
import made.archive.service.storage.StorageService;

/** Ce que le contrôle d'intégrité fait de chaque verdict de l'arbitre : corruption, preuve altérée, ou rien. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FixityCheckServiceVerdictTest
{
    @Mock StorageService storageService;
    @Spy HashService hashService = new HashService();
    @Mock DocumentRepository documentRepository;
    @Mock FixityCheckResultRepository fixityCheckResultRepository;
    @Mock DocumentEncryptionService documentEncryptionService;
    @Mock NotificationService notificationService;
    @Mock UniteOrganisationnelleService uniteOrganisationnelleService;
    @Mock DocumentService documentService;
    @Mock UserRepository userRepository;
    @Mock AuditLogService auditLogService;
    @Mock MeilisearchService meilisearchService;
    @Mock PreuveIntegriteService preuveIntegriteService;
    @Mock AlerteIntegriteService alerteIntegriteService;
    @Mock made.archive.security.ControleCleChiffrementService controleCle;
    @InjectMocks FixityCheckService service;

    private Document document;

    @BeforeEach
    void init() throws Exception
    {
        document = new Document();
        document.setId(UUID.randomUUID());
        document.setTitre("Facture");
        document.setStatus(DocumentStatus.ACTIVE);
        document.setStorageKey("pdfa/x.pdf");
        when(documentRepository.findAllById(any())).thenReturn(List.of(document));
        when(storageService.download(anyString())).thenAnswer(i -> new ByteArrayInputStream(new byte[] { 1 }));
        when(documentEncryptionService.decrypt(any())).thenReturn("contenu".getBytes());
        when(fixityCheckResultRepository.findByDocumentId(any())).thenReturn(Optional.empty());
    }

    private void verdict(Verdict v, String raison)
    {
        when(preuveIntegriteService.evaluer(any(), anyString())).thenReturn(new Constat(v, raison, false));
    }

    @Test
    void ok_aucuneAlerteEtLeDocumentResteActif()
    {
        verdict(Verdict.OK, null);

        service.verifyDocumentsByIds(List.of(document.getId()));

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        verify(alerteIntegriteService, never()).documentPreuveAlteree(any(), anyString());
        verify(notificationService, never()).notifier(any(), any(), anyString());
    }

    @Test
    void cleDeChiffrementDifferente_neCorrompPasLesDocuments_etSuspendLeControle()
    {
        org.mockito.Mockito.doThrow(new made.archive.exception.CleChiffrementException("clé différente"))
            .when(controleCle).exigerCleValide();

        assertThatThrownBy(() -> service.verifyDocumentsByIds(List.of(document.getId())))
            .isInstanceOf(made.archive.exception.CleChiffrementException.class);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        verify(alerteIntegriteService).cleChiffrementAnormale("clé différente");
        verify(storageService, never()).download(anyString());
        verify(meilisearchService, never()).deleteDocument(anyString());
        verify(notificationService, never()).notifier(any(), any(), anyString());
    }

    @Test
    void echecDeDechiffrementCauseParLaCle_neMarquePasCorrompu()
    {
        when(documentEncryptionService.decrypt(any()))
            .thenThrow(new made.archive.exception.CleChiffrementException("clé différente"));

        service.verifyDocumentsByIds(List.of(document.getId()));

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        verify(alerteIntegriteService).cleChiffrementAnormale("clé différente");
        verify(meilisearchService, never()).deleteDocument(anyString());
    }

    @Test
    void echecDeDechiffrementAvecCleValide_resteUneVraieCorruption()
    {
        when(documentEncryptionService.decrypt(any())).thenThrow(new made.archive.exception.BusinessException("tag GCM invalide"));

        service.verifyDocumentsByIds(List.of(document.getId()));

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.CORRUPTED);
    }

    @Test
    void fichierAltere_marqueCorrompuEtRetireDeLIndex()
    {
        verdict(Verdict.FICHIER_ALTERE, "fichier non conforme");

        service.verifyDocumentsByIds(List.of(document.getId()));

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.CORRUPTED);
        verify(meilisearchService).deleteDocument(document.getId().toString());
        verify(alerteIntegriteService, never()).documentPreuveAlteree(any(), anyString());
    }

    @Test
    void preuveAlteree_neCorrompPasLeDocument_etAlerteSeulementLAdministration()
    {
        verdict(Verdict.PREUVE_ALTEREE, "empreinte en base falsifiée");

        service.verifyDocumentsByIds(List.of(document.getId()));

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.ACTIVE);          // pas de faux "corrompu"
        verify(meilisearchService, never()).deleteDocument(anyString());            // reste trouvable
        verify(alerteIntegriteService).documentPreuveAlteree(document, "empreinte en base falsifiée");
        verify(notificationService, never()).notifier(any(), any(), anyString());   // pas d'alerte aux utilisateurs
    }

    @Test
    void preuveAlteree_laMemeAnomalieNEstSignaleeQuUneFois()
    {
        verdict(Verdict.PREUVE_ALTEREE, "empreinte en base falsifiée");
        FixityCheckResult deja = new FixityCheckResult();
        deja.setResult(CheckResult.PREUVE_ALTEREE);
        deja.setRaison("empreinte en base falsifiée");
        when(fixityCheckResultRepository.findByDocumentId(any())).thenReturn(Optional.of(deja));

        service.verifyDocumentsByIds(List.of(document.getId()));

        verify(alerteIntegriteService, never()).documentPreuveAlteree(any(), anyString());
        assertThat(deja.getResult()).isEqualTo(CheckResult.PREUVE_ALTEREE);

        // une anomalie DIFFÉRENTE est de nouveau signalée
        verdict(Verdict.PREUVE_ALTEREE, "signature invalide");
        service.verifyDocumentsByIds(List.of(document.getId()));
        verify(alerteIntegriteService, times(1)).documentPreuveAlteree(document, "signature invalide");
    }
}
