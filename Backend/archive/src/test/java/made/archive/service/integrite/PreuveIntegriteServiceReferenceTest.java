package made.archive.service.integrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import made.archive.config.HsmProperties;
import made.archive.entite.Document;
import made.archive.entite.User;
import made.archive.security.HsmKeyStoreService;
import made.archive.security.PkiService;
import made.archive.service.document.HashService;
import made.archive.service.integrite.HorodatageVerificationService.Etat;
import made.archive.service.integrite.ManifestePreuveService.Manifeste;
import made.archive.service.integrite.PreuveIntegriteService.EtatReference;

/**
 * Étape 1 de tout contrôle : l'empreinte SHA-256 enregistrée en base est confrontée à des preuves qu'un accès à la
 * base ne permet pas de refaire — avec une VRAIE clé dans un KeyStore temporaire.
 */
class PreuveIntegriteServiceReferenceTest
{
    private static final String HASH = "a".repeat(64);
    private static final String AUTRE = "b".repeat(64);

    private HsmKeyStoreService hsm;
    private HorodatageVerificationService jetons;
    private ManifestePreuveService manifestes;
    private PreuveIntegriteService service;
    private User editeur;
    private String alias;

    @BeforeEach
    void init() throws Exception
    {
        Path dossier = Files.createTempDirectory("hsm-ref");
        HsmProperties props = new HsmProperties();
        props.setKeystorePath(dossier.resolve("hsm.p12").toString());
        props.setKeystorePassword("mot-de-passe-de-test");
        hsm = new HsmKeyStoreService(props);
        ReflectionTestUtils.invokeMethod(hsm, "init");

        editeur = new User();
        editeur.setId(UUID.randomUUID());
        alias = "editor-" + editeur.getId();
        editeur.setPkiKeyAlias(alias);
        hsm.storePrivateKey(alias, new PkiService().generateNativeKeyPair());

        jetons = mock(HorodatageVerificationService.class);
        manifestes = mock(ManifestePreuveService.class);
        when(jetons.verifier(any(), any())).thenReturn(Etat.ABSENT);
        when(manifestes.lire(any())).thenReturn(Optional.empty());

        service = new PreuveIntegriteService(hsm, new PkiService(), mock(HashService.class), jetons, manifestes);
    }

    private Document document(String hash, String signature)
    {
        Document d = new Document();
        d.setId(UUID.randomUUID());
        d.setUploadedBy(editeur);
        d.setPdfaSha256(hash);
        d.setPkiSignature(signature);
        return d;
    }

    @Test
    void une_signature_valide_rend_l_empreinte_authentique()
    {
        Document d = document(HASH, hsm.sign(alias, HASH));

        var reference = service.authentifierEmpreinte(d);

        assertThat(reference.etat()).isEqualTo(EtatReference.AUTHENTIQUE);
        assertThat(reference.faits()).isEmpty();
    }

    @Test
    void une_empreinte_reecrite_en_base_sans_refaire_la_signature_est_alteree()
    {
        Document d = document(AUTRE, hsm.sign(alias, HASH));

        var reference = service.authentifierEmpreinte(d);

        assertThat(reference.etat()).isEqualTo(EtatReference.ALTEREE);
        assertThat(reference.faits()).anyMatch(f -> f.contains("signature du hash"));
    }

    @Test
    void un_document_sans_aucune_preuve_n_est_pas_verifiable_mais_pas_altere()
    {
        Document d = document(HASH, null);

        assertThat(service.authentifierEmpreinte(d).etat()).isEqualTo(EtatReference.NON_VERIFIABLE);
    }

    @Test
    void un_manifeste_signe_qui_atteste_une_autre_empreinte_la_declare_alteree()
    {
        Document d = document(AUTRE, null);
        String signature = hsm.sign(alias, HASH);
        when(manifestes.lire(d.getId())).thenReturn(Optional.of(new Manifeste(1, d.getId().toString(), HASH, "o", 1L, 1L,
            "2026-01-01T00:00:00Z", "cle", signature, null, null)));

        var reference = service.authentifierEmpreinte(d);

        assertThat(reference.etat()).isEqualTo(EtatReference.ALTEREE);
        assertThat(reference.faits()).anyMatch(f -> f.contains("manifeste"));
    }

    @Test
    void un_jeton_d_horodatage_qui_atteste_une_autre_empreinte_la_declare_alteree()
    {
        Document d = document(HASH, hsm.sign(alias, HASH));
        when(jetons.verifier(any(), any())).thenReturn(Etat.EMPREINTE_DIFFERENTE);

        var reference = service.authentifierEmpreinte(d);

        assertThat(reference.etat()).isEqualTo(EtatReference.ALTEREE);
        assertThat(reference.faits()).anyMatch(f -> f.contains("jeton d'horodatage"));
    }

    @Test
    void un_jeton_d_un_tiers_reconnu_suffit_a_authentifier_une_empreinte_non_signee()
    {
        Document d = document(HASH, null);
        when(jetons.verifier(any(), any())).thenReturn(Etat.ATTESTE_ANCRE);

        assertThat(service.authentifierEmpreinte(d).etat()).isEqualTo(EtatReference.AUTHENTIQUE);
    }
}
