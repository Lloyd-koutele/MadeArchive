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

/**
 * Révocation d'une clé de signature (compte supprimé) : la clé privée disparaît, la clé publique reste lisible pour
 * vérifier les signatures déjà produites ; la suppression complète, elle, est réservée aux clés qui n'ont jamais signé.
 */
@Tag("unit")
class HsmKeyStoreServiceRevocationTest
{
    private static final String HASH = "ab".repeat(32);

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

    @Test
    void apresRevocation_ilEstImpossibleDeSigner_maisLesSignaturesDejaProduitesSontVerifiables()
    {
        hsm.provisionnerCle("editor-1");
        String signature = hsm.sign("editor-1", HASH);

        assertThat(hsm.revoquerCle("editor-1")).isTrue();

        assertThatThrownBy(() -> hsm.sign("editor-1", HASH)).isInstanceOf(BusinessException.class);
        assertThat(hsm.verifier("editor-1", HASH, signature)).contains(true);
        assertThat(hsm.verifier("editor-1", "cd".repeat(32), signature)).contains(false);
        assertThat(hsm.clePublique("editor-1")).isPresent();
    }

    @Test
    void laRevocationSurvitAuRechargementDuFichier()
    {
        hsm.provisionnerCle("editor-2");
        String signature = hsm.sign("editor-2", HASH);
        hsm.revoquerCle("editor-2");

        HsmKeyStoreService relu = nouveau();

        assertThatThrownBy(() -> relu.sign("editor-2", HASH)).isInstanceOf(BusinessException.class);
        assertThat(relu.verifier("editor-2", HASH, signature)).contains(true);
        assertThat(relu.clePubliqueRelue("editor-2")).isPresent();
    }

    @Test
    void revoquerEstIdempotent_etIgnoreUnAliasAbsent()
    {
        hsm.provisionnerCle("editor-3");

        assertThat(hsm.revoquerCle("editor-3")).isTrue();
        assertThat(hsm.revoquerCle("editor-3")).isFalse();   // déjà révoquée : rien à faire
        assertThat(hsm.revoquerCle("absent")).isFalse();
        assertThat(hsm.revoquerCle(null)).isFalse();
        assertThat(hsm.clePublique("editor-3")).isPresent();
    }

    @Test
    void uneCleRevoqueeNEstPasRemplaceeParUneNouvelleGeneration()
    {
        var publique = hsm.provisionnerCle("editor-4");
        hsm.revoquerCle("editor-4");

        // L'alias existe encore (certificat) : provisionnerCle ne régénère rien et renvoie la même clé publique.
        assertThat(hsm.provisionnerCle("editor-4").getEncoded()).isEqualTo(publique.getEncoded());
        assertThatThrownBy(() -> hsm.sign("editor-4", HASH)).isInstanceOf(BusinessException.class);
    }

    @Test
    void supprimerCle_retireToutesLesEntrees_cleEtCertificat()
    {
        hsm.provisionnerCle("editor-5");

        assertThat(hsm.supprimerCle("editor-5")).isTrue();

        assertThat(hsm.hasKey("editor-5")).isFalse();
        assertThat(hsm.clePublique("editor-5")).isEmpty();
        assertThat(hsm.supprimerCle("editor-5")).isFalse();
        assertThat(hsm.supprimerCle(null)).isFalse();
    }

    @Test
    void lesAutresClesNeSontPasTouchees()
    {
        hsm.provisionnerCle("editor-6");
        hsm.provisionnerCle("editor-7");

        hsm.revoquerCle("editor-6");

        assertThat(hsm.sign("editor-7", HASH)).isNotBlank();
    }
}
