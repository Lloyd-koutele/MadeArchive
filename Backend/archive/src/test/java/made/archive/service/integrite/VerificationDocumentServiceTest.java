package made.archive.service.integrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import made.archive.dto.ChaineAuditVerificationDto;
import made.archive.dto.RapportVerificationDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.CleChiffrementException;
import made.archive.repository.UserRepository;
import made.archive.security.DocumentEncryptionService;
import made.archive.service.audit.AuditChainService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.document.DocumentService;
import made.archive.service.document.HashService;
import made.archive.service.integrite.PreuveIntegriteService.Constat;
import made.archive.service.integrite.PreuveIntegriteService.ExamenPreuves;
import made.archive.service.integrite.PreuveIntegriteService.Gravite;
import made.archive.service.integrite.PreuveIntegriteService.Point;
import made.archive.service.integrite.PreuveIntegriteService.Verdict;
import made.archive.service.storage.StorageService;

/**
 * Vérification instantanée d'un document : chaque situation (intact, fichier remplacé, base réécrite, fichier
 * illisible, journal rompu) doit produire le bon verdict ET un rapport PDF lisible, sans jamais modifier le document.
 */
class VerificationDocumentServiceTest
{
    private static final String HASH = "a".repeat(64);

    private DocumentService documentService;
    private StorageService storage;
    private DocumentEncryptionService encryption;
    private HashService hashService;
    private PreuveIntegriteService preuves;
    private HorodatageVerificationService jetons;
    private AuditChainService chaine;
    private AuditLogService audit;
    private VerificationDocumentService service;

    private final UserDetails principal = mock(UserDetails.class);
    private final UUID id = UUID.randomUUID();
    private Document doc;

    @BeforeEach
    void init() throws Exception
    {
        documentService = mock(DocumentService.class);
        storage = mock(StorageService.class);
        encryption = mock(DocumentEncryptionService.class);
        hashService = mock(HashService.class);
        preuves = mock(PreuveIntegriteService.class);
        when(preuves.authentifierEmpreinte(any())).thenReturn(
            new PreuveIntegriteService.Reference(PreuveIntegriteService.EtatReference.AUTHENTIQUE, List.of()));
        jetons = mock(HorodatageVerificationService.class);
        chaine = mock(AuditChainService.class);
        audit = mock(AuditLogService.class);
        UserRepository users = mock(UserRepository.class);
        service = new VerificationDocumentService(documentService, storage, encryption, hashService, preuves, jetons,
            chaine, audit, users);

        User u = new User();
        u.setEmail("lecteur@univ.sn");
        when(principal.getUsername()).thenReturn("lecteur@univ.sn");
        when(users.findByEmail("lecteur@univ.sn")).thenReturn(Optional.of(u));

        UniteOrganisationnelle uo = new UniteOrganisationnelle();
        uo.setId(7L);
        doc = new Document();
        doc.setId(id);
        doc.setTitre("Bulletin S5");
        doc.setVersion(1L);
        doc.setStorageKey("docs/x");
        doc.setPdfaSha256(HASH);
        doc.setUniteOrganisationnelle(uo);
        when(documentService.resolveDocumentPourAttestation(id, principal)).thenReturn(doc);

        when(storage.download("docs/x")).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        when(encryption.decrypt(any())).thenReturn("contenu".getBytes());
        when(hashService.calculateFromBytes(any())).thenReturn(HASH);
        when(preuves.examinerPreuves(doc)).thenReturn(new ExamenPreuves(true, true, List.of(
            new Point("SIGNATURE_HASH", Gravite.OK, "Signature du hash PDF/A valide (clé du HSM)"),
            new Point("HORODATAGE", Gravite.OK, "Horodatage RFC 3161 valide, certificat de l'autorité reconnu"))));
        when(preuves.evaluer(any(), anyString())).thenReturn(new Constat(Verdict.OK, null, false));
        when(chaine.verifierChaine(Set.of(7L))).thenReturn(ChaineAuditVerificationDto.builder()
            .chaineIntacte(true).nombreEntreesChainees(42).ruptures(List.of()).anomaliesGlobales(List.of()).build());
    }

    private RapportVerificationDto.Etape etape(RapportVerificationDto r, String code)
    {
        return r.getEtapes().stream().filter(e -> e.getCode().equals(code)).findFirst().orElseThrow();
    }

    private void pdfLisible(RapportVerificationDto r)
    {
        byte[] pdf = new RapportVerificationPdfService().genererPdf(r);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    @Test
    void documentIntact_estConforme_etLeRapportEstTracePuisImprimable()
    {
        RapportVerificationDto r = service.verifier(id, principal);

        assertThat(r.getVerdict()).isEqualTo(RapportVerificationDto.CONFORME);
        assertThat(r.isDegrade()).isFalse();
        assertThat(r.getEmpreinteRecalculee()).isEqualTo(HASH).isEqualTo(r.getEmpreinteEnregistree());
        assertThat(etape(r, "EMPREINTE").getStatut()).isEqualTo("OK");
        assertThat(etape(r, "HORODATAGE").getStatut()).isEqualTo("OK");
        assertThat(etape(r, "JOURNAL").getStatut()).isEqualTo("OK");
        verify(audit).log(any(User.class), eq(AuditAction.FIXITY_CHECK_DEMANDE), eq(AuditCible.DOCUMENT), eq(id.toString()),
            eq(7L), anyString(), eq(true));
        pdfLisible(r);
    }

    @Test
    void l_etape_1_est_en_tete_du_rapport_et_precede_la_comparaison_du_fichier()
    {
        RapportVerificationDto r = service.verifier(id, principal);

        assertThat(r.getEtapes().get(0).getCode()).isEqualTo("REFERENCE");
        assertThat(r.getEtapes().get(0).getStatut()).isEqualTo("OK");
        assertThat(r.getEtapes().stream().map(e -> e.getCode()).toList().indexOf("REFERENCE"))
            .isLessThan(r.getEtapes().stream().map(e -> e.getCode()).toList().indexOf("EMPREINTE"));
    }

    @Test
    void une_empreinte_enregistree_alteree_est_signalee_des_l_etape_1()
    {
        when(preuves.authentifierEmpreinte(any())).thenReturn(new PreuveIntegriteService.Reference(
            PreuveIntegriteService.EtatReference.ALTEREE, List.of("la signature du hash ne correspond pas à l'empreinte enregistrée")));
        when(preuves.evaluer(any(), anyString())).thenReturn(
            new Constat(Verdict.PREUVE_ALTEREE, "Fichier conforme mais preuves en base altérées", false));

        RapportVerificationDto r = service.verifier(id, principal);

        assertThat(etape(r, "REFERENCE").getStatut()).isEqualTo("ECHEC");
        assertThat(etape(r, "REFERENCE").getDetail()).contains("signature du hash");
        assertThat(r.getVerdict()).isEqualTo(RapportVerificationDto.PREUVES_ALTEREES);
    }

    @Test
    void fichierRemplace_estDeclareAltere()
    {
        when(hashService.calculateFromBytes(any())).thenReturn("b".repeat(64));
        when(preuves.evaluer(any(), anyString())).thenReturn(
            new Constat(Verdict.FICHIER_ALTERE, "Fichier différent de l'empreinte attestée par une preuve indépendante", false));

        RapportVerificationDto r = service.verifier(id, principal);

        assertThat(r.getVerdict()).isEqualTo(RapportVerificationDto.FICHIER_ALTERE);
        assertThat(etape(r, "EMPREINTE").getStatut()).isEqualTo("ECHEC");
        assertThat(r.getConclusion()).contains("preuve indépendante");
        verify(audit).log(any(User.class), eq(AuditAction.FIXITY_CHECK_DEMANDE), eq(AuditCible.DOCUMENT), anyString(),
            eq(7L), anyString(), eq(false));
        pdfLisible(r);
    }

    @Test
    void baseReecrite_leFichierSain_estSignaleCommePreuvesAlterees_pasCommeCorrompu()
    {
        when(preuves.examinerPreuves(doc)).thenReturn(new ExamenPreuves(false, true, List.of(
            new Point("ENREGISTREMENT", Gravite.ECHEC, "Enregistrement du document NON conforme à sa signature"),
            new Point("HORODATAGE", Gravite.OK, "Horodatage RFC 3161 valide, certificat de l'autorité reconnu"))));
        when(preuves.evaluer(any(), anyString())).thenReturn(
            new Constat(Verdict.PREUVE_ALTEREE, "Fichier conforme (jeton d'horodatage) mais preuves en base altérées", false));

        RapportVerificationDto r = service.verifier(id, principal);

        assertThat(r.getVerdict()).isEqualTo(RapportVerificationDto.PREUVES_ALTEREES);
        assertThat(etape(r, "ENREGISTREMENT").getStatut()).isEqualTo("ECHEC");
        assertThat(etape(r, "HORODATAGE").getStatut()).isEqualTo("OK");
        pdfLisible(r);
    }

    @Test
    void ancienDocumentSansPreuve_estConformeMaisDegrade()
    {
        when(preuves.evaluer(any(), anyString())).thenReturn(new Constat(Verdict.OK, null, true));

        RapportVerificationDto r = service.verifier(id, principal);

        assertThat(r.getVerdict()).isEqualTo(RapportVerificationDto.CONFORME);
        assertThat(r.isDegrade()).isTrue();
        assertThat(r.getConclusion()).contains("dégradée");
    }

    @Test
    void fichierIntrouvable_nEstPasUneConclusion()
    {
        when(storage.download("docs/x")).thenReturn(null);

        RapportVerificationDto r = service.verifier(id, principal);

        assertThat(r.getVerdict()).isEqualTo(RapportVerificationDto.NON_VERIFIABLE);
        assertThat(etape(r, "FICHIER").getStatut()).isEqualTo("ECHEC");
        verify(preuves, never()).evaluer(any(), anyString());
        pdfLisible(r);
    }

    @Test
    void echecDeDechiffrement_balisGcmInvalide_estUneAlteration()
    {
        when(encryption.decrypt(any())).thenThrow(new IllegalStateException("Tag mismatch"));

        RapportVerificationDto r = service.verifier(id, principal);

        assertThat(r.getVerdict()).isEqualTo(RapportVerificationDto.FICHIER_ALTERE);
        assertThat(etape(r, "DECHIFFREMENT").getStatut()).isEqualTo("ECHEC");
    }

    @Test
    void cleDeChiffrementDifferente_neCorrompPasLeDocument()
    {
        when(encryption.decrypt(any())).thenThrow(new CleChiffrementException("clé différente"));

        RapportVerificationDto r = service.verifier(id, principal);

        assertThat(r.getVerdict()).isEqualTo(RapportVerificationDto.NON_VERIFIABLE);
        assertThat(etape(r, "DECHIFFREMENT").getStatut()).isEqualTo("ALERTE");
    }

    @Test
    void journalRompu_estSignaleSansDeclarerLeDocumentCorrompu()
    {
        when(chaine.verifierChaine(Set.of(7L))).thenReturn(ChaineAuditVerificationDto.builder()
            .chaineIntacte(false).nombreEntreesChainees(42).rupturesHorsPerimetre(true)
            .ruptures(List.of()).anomaliesGlobales(List.of()).build());

        RapportVerificationDto r = service.verifier(id, principal);

        assertThat(r.getVerdict()).isEqualTo(RapportVerificationDto.CONFORME);
        assertThat(etape(r, "JOURNAL").getStatut()).isEqualTo("ECHEC");
        assertThat(etape(r, "JOURNAL").getDetail()).contains("ruptures existent ailleurs");
    }

    @Test
    void verificationEnLectureSeule_neTouchePasAuDocument()
    {
        Document avant = new Document();
        avant.setId(id);
        avant.setPdfaSha256(HASH);
        service.verifier(id, principal);
        assertThat(doc.getPdfaSha256()).isEqualTo(avant.getPdfaSha256());
        assertThat(doc.getStatus()).isNull();
        verify(audit, never()).log(any(User.class), eq(AuditAction.DOCUMENT_CORRUPTION_DETECTEE), any(), any(), any(), any(), anyBoolean());
    }
}
