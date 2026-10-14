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

    private HsmKeyStoreService service()
    {
        HsmProperties props = new HsmProperties();
        props.setType("PKCS11");
        props.setPkcs11Library(bibliotheque);
        props.setPin(PIN);
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
