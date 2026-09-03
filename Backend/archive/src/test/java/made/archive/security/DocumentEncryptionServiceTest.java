package made.archive.security;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import made.archive.config.StorageEncryptionProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vérifie le chiffrement au repos (AES-256-GCM, voir 5.5.3 du mémoire) : le
 * round-trip doit restituer EXACTEMENT le contenu d'origine, et toute
 * altération du chiffré doit faire échouer le déchiffrement plutôt que de
 * rendre silencieusement un contenu corrompu — c'est le tag d'authentification
 * GCM qui porte cette garantie, pas une vérification ajoutée après coup.
 */
@Tag("unit")
class DocumentEncryptionServiceTest
{
    private DocumentEncryptionService service;

    @BeforeEach
    void setUp()
    {
        byte[] cle = new byte[32];
        new SecureRandom().nextBytes(cle);

        StorageEncryptionProperties proprietes = new StorageEncryptionProperties();
        proprietes.setKey(Base64.getEncoder().encodeToString(cle));

        service = new DocumentEncryptionService(proprietes);
    }

    @Test
    void chiffrerPuisDechiffrerRestitueLeContenuOriginal()
    {
        byte[] original = "Contenu PDF/A de test — accents éàç, %PDF-1.7 en-tête factice".getBytes();

        byte[] chiffre = service.encrypt(original);
        byte[] dechiffre = service.decrypt(chiffre);

        assertThat(dechiffre).isEqualTo(original);
    }

    @Test
    void leChiffreEstDifferentDuContenuOriginal()
    {
        byte[] original = "contenu en clair".getBytes();

        byte[] chiffre = service.encrypt(original);

        assertThat(chiffre).isNotEqualTo(original);
    }

    @Test
    void deuxChiffrementsDuMemeContenuProduisentDesResultatsDifferents()
    {
        // IV aléatoire à chaque appel — sans ça, deux documents identiques
        // produiraient le même chiffré, une fuite d'information.
        byte[] original = "même contenu".getBytes();

        byte[] chiffre1 = service.encrypt(original);
        byte[] chiffre2 = service.encrypt(original);

        assertThat(chiffre1).isNotEqualTo(chiffre2);
    }

    @Test
    void unChiffreAltereEchoueAuDechiffrement()
    {
        byte[] original = "contenu à protéger".getBytes();
        byte[] chiffre = service.encrypt(original);

        // Altère un octet du corps chiffré (après les 12 octets d'IV) —
        // simule un fichier corrompu ou falsifié dans le stockage.
        chiffre[chiffre.length - 1] ^= 0x01;

        assertThatThrownBy(() -> service.decrypt(chiffre))
            .isInstanceOf(Exception.class);
    }
}
