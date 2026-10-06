package made.archive.service.integrite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ArbreMerkleTest
{
    private static FeuilleDocument f(String id, String pdfa, String original, String signature)
    {
        return new FeuilleDocument(UUID.fromString(id), pdfa, original, signature);
    }

    private static List<FeuilleDocument> lot(int n)
    {
        List<FeuilleDocument> l = new ArrayList<>();
        for (int i = 0; i < n; i++)
        {
            l.add(f(new UUID(0, i).toString(), "p" + i, "o" + i, "s" + i));
        }
        return l;
    }

    @Test
    void lOrdreDEntreeNImportePas()
    {
        List<FeuilleDocument> a = lot(7);
        List<FeuilleDocument> b = new ArrayList<>(a);
        Collections.reverse(b);

        assertThat(ArbreMerkle.racine(a)).isEqualTo(ArbreMerkle.racine(b)).hasSize(64);
    }

    @Test
    void modifierUnSeulChampDUneFeuilleChangeLaRacine()
    {
        String reference = ArbreMerkle.racine(lot(5));

        for (int champ = 0; champ < 3; champ++)
        {
            List<FeuilleDocument> l = lot(5);
            FeuilleDocument v = l.get(3);
            l.set(3, new FeuilleDocument(v.id(),
                champ == 0 ? "autre" : v.pdfaSha256(),
                champ == 1 ? "autre" : v.originalSha256(),
                champ == 2 ? "autre" : v.signatureEnregistrement()));
            assertThat(ArbreMerkle.racine(l)).as("champ %d", champ).isNotEqualTo(reference);
        }
    }

    @Test
    void retirerOuAjouterUneFeuilleChangeLaRacine()
    {
        List<FeuilleDocument> l = lot(6);
        String reference = ArbreMerkle.racine(l);

        List<FeuilleDocument> moins = new ArrayList<>(l);
        moins.remove(5);
        List<FeuilleDocument> plus = new ArrayList<>(l);
        plus.add(f(new UUID(1, 1).toString(), "x", "y", "z"));

        assertThat(ArbreMerkle.racine(moins)).isNotEqualTo(reference);
        assertThat(ArbreMerkle.racine(plus)).isNotEqualTo(reference);
    }

    @Test
    void unNombreImpairNEstJamaisEquivalentAUneDuplication()
    {
        // 3 feuilles : la dernière est promue, pas dupliquée — sinon [a,b,c] et [a,b,c,c] donneraient la même racine
        List<FeuilleDocument> trois = lot(3);
        List<FeuilleDocument> quatre = new ArrayList<>(trois);
        quatre.add(f(new UUID(0, 2).toString(), "p2", "o2", "s2")); // même contenu que la 3e, même id -> doublon exact

        assertThat(ArbreMerkle.racine(trois)).isNotEqualTo(ArbreMerkle.racine(quatre));
    }

    @Test
    void uneFeuilleUniqueEtUnLotVide()
    {
        List<FeuilleDocument> une = lot(1);
        assertThat(ArbreMerkle.racine(une)).isEqualTo(ArbreMerkle.feuille(une.get(0)));
        assertThat(ArbreMerkle.racine(List.of())).isEmpty();
    }
}
