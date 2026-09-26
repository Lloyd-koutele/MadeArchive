package made.archive.util;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Couvre la normalisation utilisée pour détecter les doublons de noms
 * (UO, Dossiers, emplacements physiques) — voir Javadoc de NormalisationNoms.
 */
@Tag("unit")
class NormalisationNomsTest
{
    @Test
    void ignoreLaCasse()
    {
        assertThat(NormalisationNoms.normaliser("Éducation"))
            .isEqualTo(NormalisationNoms.normaliser("éducation"))
            .isEqualTo(NormalisationNoms.normaliser("ÉDUCATION"));
    }

    @Test
    void retireLesAccents()
    {
        assertThat(NormalisationNoms.normaliser("Éducation Nationale"))
            .isEqualTo(NormalisationNoms.normaliser("Education Nationale"));
    }

    @Test
    void retireIntegralementLesEspacesPasSeulementLesReduire()
    {
        // Exactement le cas signalé : "Dossier 4" et "Dossier4" doivent être
        // détectés comme LE MÊME nom, pas juste "Dossier  4" == "Dossier 4".
        assertThat(NormalisationNoms.normaliser("Dossier 4"))
            .isEqualTo(NormalisationNoms.normaliser("Dossier4"))
            .isEqualTo(NormalisationNoms.normaliser("  DOSSIER   4  "))
            .isEqualTo(NormalisationNoms.normaliser("Dôssier 4"));
    }

    @Test
    void nomsReellementDifferentsRestentDifferents()
    {
        assertThat(NormalisationNoms.normaliser("Dossier 4"))
            .isNotEqualTo(NormalisationNoms.normaliser("Dossier 5"));
        assertThat(NormalisationNoms.normaliser("Archives"))
            .isNotEqualTo(NormalisationNoms.normaliser("Archive"));
    }

    @Test
    void toleresValeurNulle()
    {
        assertThat(NormalisationNoms.normaliser(null)).isEmpty();
    }
}
