package made.archive.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import made.archive.config.HsmProperties;
import made.archive.exception.BusinessException;

/**
 * Le SoftHSM réel (PKCS#11) : clés générées DANS le jeton, signature, vérification, HMAC calculé par le jeton.
 *
 * Ignoré quand SoftHSM n'est pas installé (poste de développement) ; exécuté en CI (paquet softhsm2) et dans l'image de
 * l'application. build.gradle fixe SOFTHSM2_CONF vers build/softhsm/softhsm2.conf : la configuration et le jeton de test
 * sont créés ici, dans le dossier de build, sans toucher à un éventuel SoftHSM du système.
 */
class HsmKeyStoreServicePkcs11Test
{
    private static final String PIN = "123456";
    private static final String SO_PIN = "654321";
    private static final List<String> BIBLIOTHEQUES = List.of(
        "/usr/lib/softhsm/libsofthsm2.so",
        "/usr/lib/aarch64-linux-gnu/softhsm/libsofthsm2.so",
        "/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so",
        "/opt/homebrew/lib/softhsm/libsofthsm2.so",
        "/usr/local/lib/softhsm/libsofthsm2.so");

    private static String bibliotheque;

    @BeforeAll
    static void preparerLeJeton() throws Exception
    {
        String conf = System.getenv("SOFTHSM2_CONF");
        assumeTrue(conf != null && !conf.isBlank(), "SOFTHSM2_CONF non défini (hors Gradle)");
        bibliotheque = BIBLIOTHEQUES.stream().filter(c -> new File(c).isFile()).findFirst().orElse(null);
        assumeTrue(bibliotheque != null, "SoftHSM (libsofthsm2.so) absent de ce poste");
        assumeTrue(commandeDisponible("softhsm2-util"), "softhsm2-util absent de ce poste");

        Path fichierConf = Path.of(conf);
        Path tokens = fichierConf.getParent().resolve("tokens");
        supprimer(tokens);
        Files.createDirectories(tokens);
        certificatsRevoques = fichierConf.getParent().resolve("certificats-revoques.p12");
        Files.deleteIfExists(certificatsRevoques);
        Files.writeString(fichierConf, "directories.tokendb = " + tokens.toAbsolutePath() + "\nobjectstore.backend = file\nlog.level = ERROR\n");

        ProcessBuilder pb = new ProcessBuilder("softhsm2-util", "--init-token", "--free", "--label", "test",
            "--so-pin", SO_PIN, "--pin", PIN).redirectErrorStream(true);
        pb.environment().put("SOFTHSM2_CONF", fichierConf.toAbsolutePath().toString());
        Process p = pb.start();
        String sortie = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as("initialisation du jeton : " + sortie).isZero();
    }

    private static boolean commandeDisponible(String commande)
    {
        try
        {
            return new ProcessBuilder(commande, "--version").redirectErrorStream(true).start().waitFor() == 0;
        }
        catch (IOException | InterruptedException e)
        {
            return false;
        }
    }

    private static void supprimer(Path dossier) throws IOException
    {
        if (!Files.exists(dossier)) return;
        try (Stream<Path> s = Files.walk(dossier))
        {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static Path certificatsRevoques;

    private HsmKeyStoreService service()
    {
        HsmProperties props = new HsmProperties();
        props.setType("PKCS11");
        props.setPkcs11Library(bibliotheque);
        props.setPin(PIN);
        props.setCertificatsRevoquesPath(certificatsRevoques.toString());
        HsmKeyStoreService s = new HsmKeyStoreService(props);
        ReflectionTestUtils.invokeMethod(s, "init");
        return s;
    }

    private static final String HASH = "ab".repeat(32);

    @Test
    void une_cle_generee_dans_le_jeton_signe_et_se_verifie_avec_sa_cle_publique()
    {
        HsmKeyStoreService hsm = service();

        PublicKey publique = hsm.provisionnerCle("editor-test-1");
        String signature = hsm.sign("editor-test-1", HASH);

        assertThat(publique.getAlgorithm()).isEqualTo("RSA");
        assertThat(hsm.hasKey("editor-test-1")).isTrue();
        assertThat(hsm.verifier("editor-test-1", HASH, signature)).contains(true);
        assertThat(hsm.verifier("editor-test-1", "cd".repeat(32), signature)).contains(false);
        assertThat(hsm.clePubliquePem("editor-test-1")).isPresent();
    }

    @Test
    void une_cle_existante_n_est_jamais_remplacee()
    {
        HsmKeyStoreService hsm = service();
        PublicKey premiere = hsm.provisionnerCle("editor-test-2");

        PublicKey seconde = hsm.provisionnerCle("editor-test-2");

        assertThat(seconde.getEncoded()).isEqualTo(premiere.getEncoded());
    }

    @Test
    void les_cles_survivent_a_une_nouvelle_ouverture_du_jeton()
    {
        HsmKeyStoreService avant = service();
        avant.provisionnerCle("editor-test-3");
        String signature = avant.sign("editor-test-3", HASH);

        HsmKeyStoreService apres = service();   // nouvelle instance, même jeton

        assertThat(apres.hasKey("editor-test-3")).isTrue();
        assertThat(apres.verifier("editor-test-3", HASH, signature)).contains(true);
    }

    @Test
    void une_cle_revoquee_ne_signe_plus_mais_ses_signatures_restent_verifiables_apres_reouverture_du_jeton()
    {
        HsmKeyStoreService hsm = service();
        PublicKey publique = hsm.provisionnerCle("editor-test-revoque");
        String signature = hsm.sign("editor-test-revoque", HASH);

        assertThat(hsm.revoquerCle("editor-test-revoque")).isTrue();

        // La clé privée est détruite DANS le jeton : plus aucune signature possible, même après réouverture.
        assertThatThrownBy(() -> hsm.sign("editor-test-revoque", HASH)).isInstanceOf(BusinessException.class);
        HsmKeyStoreService apres = service();
        assertThatThrownBy(() -> apres.sign("editor-test-revoque", HASH)).isInstanceOf(BusinessException.class);

        // Le certificat (clé publique) reste dans le jeton : la signature déjà produite se vérifie toujours.
        assertThat(apres.clePubliqueRelue("editor-test-revoque").orElseThrow().getEncoded()).isEqualTo(publique.getEncoded());
        assertThat(apres.verifier("editor-test-revoque", HASH, signature)).contains(true);
        assertThat(apres.revoquerCle("editor-test-revoque")).isFalse();   // idempotent
    }

    @Test
    void la_revocation_est_refusee_sans_toucher_au_jeton_si_le_fichier_des_certificats_n_est_pas_configure()
    {
        HsmKeyStoreService hsm = service();
        hsm.provisionnerCle("editor-test-sans-fichier");
        HsmProperties props = new HsmProperties();
        props.setType("PKCS11");
        props.setPkcs11Library(bibliotheque);
        props.setPin(PIN);
        HsmKeyStoreService sansFichier = new HsmKeyStoreService(props);
        ReflectionTestUtils.invokeMethod(sansFichier, "init");

        assertThatThrownBy(() -> sansFichier.revoquerCle("editor-test-sans-fichier")).isInstanceOf(BusinessException.class);

        // La clé est intacte : la révocation a échoué AVANT de supprimer quoi que ce soit.
        assertThat(sansFichier.sign("editor-test-sans-fichier", HASH)).isNotBlank();
    }

    @Test
    void un_alias_revoque_ne_recoit_jamais_une_nouvelle_cle()
    {
        HsmKeyStoreService hsm = service();
        hsm.provisionnerCle("editor-test-jamais-regenere");
        hsm.revoquerCle("editor-test-jamais-regenere");

        assertThatThrownBy(() -> hsm.provisionnerCle("editor-test-jamais-regenere")).isInstanceOf(BusinessException.class);
    }

    @Test
    void une_cle_supprimee_disparait_du_jeton_avec_son_certificat()
    {
        HsmKeyStoreService hsm = service();
        hsm.provisionnerCle("editor-test-supprime");

        assertThat(hsm.supprimerCle("editor-test-supprime")).isTrue();

        HsmKeyStoreService apres = service();
        assertThat(apres.hasKey("editor-test-supprime")).isFalse();
        assertThat(apres.clePubliqueRelue("editor-test-supprime")).isEmpty();
    }

    @Test
    void un_alias_inconnu_ne_signe_pas_et_n_est_pas_verifiable()
    {
        HsmKeyStoreService hsm = service();

        assertThatThrownBy(() -> hsm.sign("absent", HASH)).isInstanceOf(BusinessException.class);
        assertThat(hsm.verifier("absent", HASH, "00")).isEmpty();
    }

    @Test
    void le_hmac_est_calcule_par_le_jeton_avec_une_cle_creee_une_fois_et_jamais_remplacee()
    {
        HsmKeyStoreService hsm = service();

        assertThat(hsm.garantirCleSecrete("chaine-test")).isTrue();
        assertThat(hsm.garantirCleSecrete("chaine-test")).isFalse();

        Map<String, String> h = hsm.avecHmac("chaine-test", calcul -> Map.of(
            "a1", calcul.hex("abc"), "a2", calcul.hex("abc"), "b", calcul.hex("abd")));

        assertThat(h.get("a1")).hasSize(64).isEqualTo(h.get("a2")).isNotEqualTo(h.get("b"));
        // la même clé, rouverte plus tard, donne le même HMAC
        String relu = hsm.<String>avecHmac("chaine-test", calcul -> calcul.hex("abc"));
        assertThat(relu).isEqualTo(h.get("a1"));
    }
}
