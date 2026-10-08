package made.archive.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import made.archive.config.HsmProperties;
import made.archive.exception.BusinessException;

/** Clé secrète HMAC du HSM fichier : créée une fois, jamais remplacée, jamais remise à l'appelant. */
@Tag("unit")
class HsmKeyStoreServiceHmacTest
{
    @TempDir Path dossier;
    private HsmProperties proprietes;
    private HsmKeyStoreService hsm;

    @BeforeEach
    void setUp()
    {
        proprietes = new HsmProperties();
        proprietes.setKeystorePath(dossier.resolve("hsm.p12").toString());
        proprietes.setKeystorePassword("mot-de-passe-de-test");
        hsm = nouveau();
    }

    private HsmKeyStoreService nouveau()
    {
        HsmKeyStoreService s = new HsmKeyStoreService(proprietes);
        s.init();
        return s;
    }

    private static String hmac(HsmKeyStoreService service, String alias, String message)
    {
        return service.<String>avecHmac(alias, c -> c.hex(message));
    }

    @Test
    void garantirCleSecrete_creeUneFois_etNeRemplaceJamais()
    {
        assertThat(hsm.garantirCleSecrete("k")).isTrue();
        String avant = hmac(hsm, "k", "message");

        assertThat(hsm.garantirCleSecrete("k")).isFalse();
        assertThat(hmac(hsm, "k", "message")).isEqualTo(avant);
    }

    @Test
    void hmac_estDeterministe_dependDuMessage_etSurvitAuRechargementDuFichier()
    {
        hsm.garantirCleSecrete("k");
        String a = hmac(hsm, "k", "abc");

        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hmac(hsm, "k", "abc")).isEqualTo(a);
        assertThat(hmac(hsm, "k", "abd")).isNotEqualTo(a);

        // Nouvelle instance = fichier relu : la clé stockée donne le même HMAC.
        assertThat(hmac(nouveau(), "k", "abc")).isEqualTo(a);
    }

    @Test
    void deuxClesDifferentes_donnentDesHmacDifferents()
    {
        hsm.garantirCleSecrete("k1");
        hsm.garantirCleSecrete("k2");
        assertThat(hmac(hsm, "k1", "m")).isNotEqualTo(hmac(hsm, "k2", "m"));
    }

    @Test
    void aliasInconnu_ouCleNonSecrete_estRefuse() throws Exception
    {
        assertThatThrownBy(() -> hmac(hsm, "absente", "m")).isInstanceOf(BusinessException.class);

        hsm.storePrivateKey("rsa", new PkiService().generateNativeKeyPair());
        assertThatThrownBy(() -> hmac(hsm, "rsa", "m"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("pas une clé secrète");
    }
}
