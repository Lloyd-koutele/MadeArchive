package made.archive.service.document;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie les propriétés attendues des deux empreintes SHA-256 du système
 * (originalSha256 / pdfaSha256, voir 5.5.1) : déterminisme (même contenu →
 * même empreinte, sur laquelle repose la détection de doublons ET la
 * signature PKI) et sensibilité à la moindre altération (sur laquelle
 * repose le contrôle de fixité quotidien).
 */
@Tag("unit")
class HashServiceTest
{
    private final HashService service = new HashService();

    @Test
    void memeContenuProduitLaMemeEmpreinte()
    {
        byte[] contenu = "contenu identique".getBytes(StandardCharsets.UTF_8);

        String hash1 = service.calculateFromBytes(contenu);
        String hash2 = service.calculateFromBytes(contenu.clone());

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void unSeulOctetDifferentChangeCompletementLEmpreinte()
    {
        byte[] contenuA = "contenu presque identique A".getBytes(StandardCharsets.UTF_8);
        byte[] contenuB = "contenu presque identique B".getBytes(StandardCharsets.UTF_8);

        String hashA = service.calculateFromBytes(contenuA);
        String hashB = service.calculateFromBytes(contenuB);

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void lEmpreinteEstUnHexadecimal64Caracteres()
    {
        String hash = service.calculateFromBytes("test".getBytes(StandardCharsets.UTF_8));

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void verifyFromStreamConfirmeUneEmpreinteCorrecte() throws Exception
    {
        byte[] contenu = "document PDF/A".getBytes(StandardCharsets.UTF_8);
        String hashAttendu = service.calculateFromBytes(contenu);

        boolean valide = service.verifyFromStream(
            new java.io.ByteArrayInputStream(contenu), hashAttendu);

        assertThat(valide).isTrue();
    }

    @Test
    void verifyFromStreamDetecteUneEmpreinteIncorrecte() throws Exception
    {
        byte[] contenu = "document PDF/A".getBytes(StandardCharsets.UTF_8);

        boolean valide = service.verifyFromStream(
            new java.io.ByteArrayInputStream(contenu), "0".repeat(64));

        assertThat(valide).isFalse();
    }
}
