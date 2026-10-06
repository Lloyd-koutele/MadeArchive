package made.archive.service.integrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import made.archive.config.HsmProperties;
import made.archive.entite.Document;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.security.HsmKeyStoreService;
import made.archive.security.PkiService;
import made.archive.service.document.HashService;
import made.archive.service.integrite.HorodatageVerificationService.Etat;
import made.archive.service.integrite.ManifestePreuveService.Manifeste;
import made.archive.service.integrite.PreuveIntegriteService.Verdict;

/**
 * Arbitrage entre une altération du FICHIER et une altération des PREUVES en base. Vrai KeyStore HSM, vraies
 * signatures RSA ; seuls le jeton d'horodatage et le manifeste MinIO sont simulés.
 */
class PreuveIntegriteServiceTest
{
    @TempDir Path dossier;

    private HsmKeyStoreService hsm;
    private PkiService pki;
    private HashService hash;
    private HorodatageVerificationService jetons;
    private ManifestePreuveService manifestes;
    private PreuveIntegriteService service;

    private static final String ALIAS = "editor-1";
    private static final String H = "a".repeat(64);          // empreinte du fichier d'origine
    private static final String AUTRE = "b".repeat(64);      // empreinte d'un fichier remplacé

    @BeforeEach
    void init() throws Exception
    {
        HsmProperties props = new HsmProperties();
        props.setKeystorePath(dossier.resolve("hsm.p12").toString());
        props.setKeystorePassword("mot-de-passe-de-test");
        hsm = new HsmKeyStoreService(props);
        ReflectionTestUtils.invokeMethod(hsm, "init");
        pki = new PkiService();
        hash = new HashService();
        hsm.storePrivateKey(ALIAS, pki.generateNativeKeyPair());

        jetons = mock(HorodatageVerificationService.class);
        when(jetons.verifier(any(), any())).thenReturn(Etat.ABSENT);
        manifestes = mock(ManifestePreuveService.class);
        when(manifestes.lire(any())).thenReturn(Optional.empty());

        service = new PreuveIntegriteService(hsm, pki, hash, jetons, manifestes);
        service.garantirCleSysteme();
    }

    private Document documentSigne(String pdfaHash)
    {
        UniteOrganisationnelle uo = new UniteOrganisationnelle();
        uo.setId(7L);
        User editeur = new User();
        editeur.setId(UUID.randomUUID());
        editeur.setPkiKeyAlias(ALIAS);

        Document d = new Document();
        d.setId(UUID.randomUUID());
        d.setPdfaSha256(pdfaHash);
        d.setOriginalSha256("c".repeat(64));
        d.setUniteOrganisationnelle(uo);
        d.setUploadedBy(editeur);
        d.setVersion(1L);
        d.setCreateAt(LocalDateTime.of(2026, 5, 4, 10, 30, 15, 123_456_789));
        d.setStorageKey("pdfa/Facture/2026-05-04/" + d.getId() + "/f.pdf");
        d.setPkiSignature(hsm.sign(ALIAS, pdfaHash));
        service.sceller(d, ALIAS);
        return d;
    }

    // ── cas nominal ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void unDocumentIntactEstOk()
    {
        PreuveIntegriteService.Constat c = service.evaluer(documentSigne(H), H);

        assertThat(c.verdict()).isEqualTo(Verdict.OK);
        assertThat(c.degrade()).isFalse();
    }

    @Test
    void laDateEstSigneeALaSecondePourSurvivreAuxMicrosecondesDeLaBase()
    {
        Document d = documentSigne(H);
        d.setCreateAt(d.getCreateAt().withNano(123_456_000)); // précision de PostgreSQL

        assertThat(service.etatEnregistrement(d)).isEqualTo(PreuveIntegriteService.EtatEnregistrement.CONFORME);
    }

    // ── la question : qui a été modifié, le fichier ou la preuve ? ──────────────────────────────────────────

    @Test
    void empreinteModifieeEnBaseSeule_leFichierEstSain_c_estLaPreuveQuiEstAlteree()
    {
        Document d = documentSigne(H);
        d.setPdfaSha256(AUTRE); // l'attaquant ne touche que la colonne

        PreuveIntegriteService.Constat c = service.evaluer(d, H);

        assertThat(c.verdict()).isEqualTo(Verdict.PREUVE_ALTEREE);
        assertThat(c.raison()).contains("l'empreinte en base diffère");
    }

    @Test
    void fichierRemplaceSansToucherLaBase_c_est_le_fichier_qui_est_altere()
    {
        Verdict v = service.evaluer(documentSigne(H), AUTRE).verdict();

        assertThat(v).isEqualTo(Verdict.FICHIER_ALTERE);
    }

    @Test
    void fichierEtEmpreinteRemplacesSansLaCleDuHsm_c_est_detecte()
    {
        Document d = documentSigne(H);
        d.setPdfaSha256(AUTRE); // fichier remplacé ET empreinte réécrite pour correspondre

        assertThat(service.evaluer(d, AUTRE).verdict()).isEqualTo(Verdict.FICHIER_ALTERE);
    }

    @Test
    void attaquantQuiRemplaceEmpreinteSignatureEtClePubliqueEnBase_laCleDeConfianceNEstPasCelleDeLaBase() throws Exception
    {
        Document d = documentSigne(H);
        KeyPair attaquant = pki.generateNativeKeyPair();
        // Il réécrit tout ce que la base contient : empreinte, signature (avec SA clé) et clé publique de l'éditeur.
        d.setPdfaSha256(AUTRE);
        d.setPkiSignature(signerAvec(attaquant, AUTRE));
        d.getUploadedBy().setPkiPublicKey(pki.encodePublicKeyToPem(attaquant.getPublic()));

        // Le fichier, lui, est toujours l'original (empreinte H) : la signature d'enregistrement d'origine le prouve.
        PreuveIntegriteService.Constat c = service.evaluer(d, H);
        assertThat(c.verdict()).isEqualTo(Verdict.PREUVE_ALTEREE);

        // Et la vérification publique n'accepte pas la signature de l'attaquant, malgré sa clé publique en base.
        assertThat(service.verifierPreuves(d).authentique()).isFalse();
    }

    @Test
    void originalSha256Modifie_lEnregistrementNEstPlusConforme_sansCorrompreLeDocument()
    {
        Document d = documentSigne(H);
        d.setOriginalSha256("d".repeat(64)); // contourner l'anti-doublon / falsifier la provenance

        PreuveIntegriteService.Constat c = service.evaluer(d, H);

        assertThat(service.etatEnregistrement(d)).isEqualTo(PreuveIntegriteService.EtatEnregistrement.ALTERE);
        assertThat(c.verdict()).isEqualTo(Verdict.PREUVE_ALTEREE);
    }

    @Test
    void signatureDuHashRemplaceeSeule_toujoursUnePreuveAlteree()
    {
        Document d = documentSigne(H);
        d.setPkiSignature("00".repeat(256));

        assertThat(service.evaluer(d, H).verdict()).isEqualTo(Verdict.PREUVE_ALTEREE);
    }

    // ── preuves indépendantes de la base et du HSM ──────────────────────────────────────────────────────────

    @Test
    void attaquantQuiPossedeLeHsmEtResigneTout_ledJetonEtLeManifesteLeContredisent()
    {
        Document d = documentSigne(H);
        // Il remplace le fichier (empreinte AUTRE), réécrit l'empreinte, re-signe avec le HSM, re-scelle l'enregistrement.
        d.setPdfaSha256(AUTRE);
        d.setPkiSignature(hsm.sign(ALIAS, AUTRE));
        service.sceller(d, ALIAS);

        // Sans autre source, c'est indétectable à ce niveau...
        assertThat(service.evaluer(d, AUTRE).verdict()).isEqualTo(Verdict.OK);

        // ...mais le jeton d'horodatage de l'époque (autorité tierce) atteste l'empreinte d'origine
        when(jetons.verifier(any(), any())).thenReturn(Etat.EMPREINTE_DIFFERENTE);
        assertThat(service.evaluer(d, AUTRE).verdict()).isEqualTo(Verdict.FICHIER_ALTERE);
    }

    @Test
    void jetonRecycleEnBaseMaisManifesteVerrouilleIntact_lePreuveEstAltereeEtLeFichierSain()
    {
        Document d = documentSigne(H);
        when(jetons.verifier(any(), any())).thenReturn(Etat.EMPREINTE_DIFFERENTE); // jeton d'un autre document
        when(manifestes.lire(d.getId())).thenReturn(Optional.of(manifeste(d, H)));

        assertThat(service.evaluer(d, H).verdict()).isEqualTo(Verdict.PREUVE_ALTEREE);
    }

    @Test
    void jetonAncreQuiAtesteLeFichier_confirmeLeVerdictOk()
    {
        Document d = documentSigne(H);
        when(jetons.verifier(any(), any())).thenReturn(Etat.ATTESTE_ANCRE);

        assertThat(service.evaluer(d, H).verdict()).isEqualTo(Verdict.OK);
    }

    @Test
    void jetonAncreQuiAtesteLeFichierMalgreUneBaseEntierementReecrite_sauveLeDocument()
    {
        Document d = documentSigne(H);
        d.setPdfaSha256(AUTRE);
        d.setPkiSignature("00".repeat(256));
        d.setSignatureEnregistrement(null); // preuves de la base entièrement détruites
        when(jetons.verifier(any(), any())).thenReturn(Etat.ATTESTE_ANCRE);

        assertThat(service.evaluer(d, H).verdict()).isEqualTo(Verdict.PREUVE_ALTEREE);
    }

    @Test
    void manifesteDontLaSignatureNEstPasValideNEstJamaisUneAttestation()
    {
        Document d = documentSigne(H);
        Manifeste falsifie = new Manifeste(1, d.getId().toString(), H, d.getOriginalSha256(), 7L, 1L, "x", "k",
            "00".repeat(256), null, null);
        d.setPdfaSha256(AUTRE);
        when(manifestes.lire(d.getId())).thenReturn(Optional.of(falsifie));

        // il "atteste" H mais sa signature ne se vérifie pas : seules les signatures du HSM tranchent ici
        assertThat(service.evaluer(d, H).verdict()).isEqualTo(Verdict.PREUVE_ALTEREE);
        assertThat(service.evaluer(d, AUTRE).verdict()).isEqualTo(Verdict.FICHIER_ALTERE);
    }

    // ── anciens documents et mode dégradé ───────────────────────────────────────────────────────────────────

    @Test
    void ancienDocumentSansAucuneSignature_retombeSurLaComparaisonDEmpreinte_signaleeDegradee()
    {
        Document d = documentSigne(H);
        d.setPkiSignature(null);
        d.setSignatureEnregistrement(null);
        d.setSignatureEnregistrementAlias(null);

        PreuveIntegriteService.Constat intact = service.evaluer(d, H);
        PreuveIntegriteService.Constat modifie = service.evaluer(d, AUTRE);

        assertThat(intact.verdict()).isEqualTo(Verdict.OK);
        assertThat(intact.degrade()).isTrue();
        assertThat(modifie.verdict()).isEqualTo(Verdict.FICHIER_ALTERE);
    }

    @Test
    void empreinteAuthentique_refuseUneEmpreinteReecrite_maisPasUnAncienDocument()
    {
        Document sain = documentSigne(H);
        Document reecrit = documentSigne(H);
        reecrit.setPdfaSha256(AUTRE);
        Document ancien = documentSigne(H);
        ancien.setPkiSignature(null);
        ancien.setSignatureEnregistrement(null);

        assertThat(service.empreinteAuthentique(sain)).isTrue();
        assertThat(service.empreinteAuthentique(reecrit)).isFalse();   // pas d'horodatage d'une empreinte falsifiée
        assertThat(service.empreinteAuthentique(ancien)).isTrue();     // rien à contester : non bloquant
    }

    // ── vérification publique, sans le fichier ──────────────────────────────────────────────────────────────

    @Test
    void verificationPublique_authentiqueFalsifieEtManifesteEnDesaccord()
    {
        Document d = documentSigne(H);
        assertThat(service.verifierPreuves(d).authentique()).isTrue();

        Document falsifie = documentSigne(H);
        falsifie.setPdfaSha256(AUTRE);
        assertThat(service.verifierPreuves(falsifie).authentique()).isFalse();
        assertThat(service.verifierPreuves(falsifie).constats()).anyMatch(s -> s.contains("INVALIDE"));

        Document enDesaccord = documentSigne(H);
        when(manifestes.lire(enDesaccord.getId())).thenReturn(Optional.of(manifeste(enDesaccord, AUTRE)));
        assertThat(service.verifierPreuves(enDesaccord).authentique()).isFalse();
    }

    @Test
    void laClePubliqueAffichee_vientDuHsm_pasDeLaBase()
    {
        Document d = documentSigne(H);
        d.getUploadedBy().setPkiPublicKey("-----BEGIN PUBLIC KEY-----\nFAUSSE\n-----END PUBLIC KEY-----");

        String affichee = service.clePubliqueDeConfiance(d).orElseThrow();

        assertThat(affichee).startsWith("-----BEGIN PUBLIC KEY-----").doesNotContain("FAUSSE");
        assertThat(affichee).isEqualTo(hsm.clePubliquePem(ALIAS).orElseThrow());
    }

    // ── outils ──────────────────────────────────────────────────────────────────────────────────────────────

    private Manifeste manifeste(Document d, String hashAtteste)
    {
        return new Manifeste(1, d.getId().toString(), hashAtteste, d.getOriginalSha256(), 7L, 1L, "x", d.getStorageKey(),
            hsm.sign(ALIAS, hashAtteste), null, null);
    }

    private String signerAvec(KeyPair cle, String hashHex) throws Exception
    {
        java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
        s.initSign(cle.getPrivate());
        s.update(java.util.HexFormat.of().parseHex(hashHex));
        return java.util.HexFormat.of().formatHex(s.sign());
    }
}
