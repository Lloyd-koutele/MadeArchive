package made.archive.service.document;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Couvre la couche de réparation déterministe ajoutée en 09/2026 suite à
 * l'investigation qualité des regex générées par LLM (qwen2.5-coder:7b et
 * gemma4:e4b/12b testés — aucun réglage de température/format ni changement
 * de modèle local n'élimine ces défauts de façon fiable ; ils sont en
 * revanche mécaniquement détectables et corrigeables sans dépendre du LLM).
 *
 * Les cas de test reproduisent des candidats RÉELLEMENT générés pendant
 * l'investigation (voir résumé de session) plutôt que des exemples inventés.
 */
@Tag("unit")
class OllamaServiceTest
{
    // webClientBuilder/objectMapper non utilisés par repairCandidate/validateCandidate
    // (seulement par callQwenBatch, jamais appelé ici) — null est sûr.
    private final OllamaService service = new OllamaService(null, null, null);

    // ─────────────────────────────────────────────────────────────────────
    // repairCandidate : classe [...\s...] gourmande -> paresseuse
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void rendParesseuseUneClasseAvecSEtQuantificateurGourmandPlus()
    {
        // Régression confirmée sur le document "attestation" : ce motif capturait
        // "Lloyd Marvin KOUTELE\nDate de naissance " (déborde sur le champ suivant).
        // Rien ne suit le groupe dans le motif d'origine -> une borne est ajoutée
        // (sinon le "+?" dégénère vers une capture d'un seul caractère, voir plus bas).
        String candidat = "(?:Prénoms\\s*et\\s*Nom\\s*:)\\s*([A-Za-zÀ-ÿ\\s]+)";

        String repare = service.repairCandidate(candidat);

        assertThat(repare).isEqualTo(
            "(?:Prénoms\\s*et\\s*Nom\\s*:)\\s*([A-Za-zÀ-ÿ\\s]+?)(?=\\n\\s*\\S.{0,60}?:|\\n\\s*\\n|$)");
    }

    @Test
    void rendParesseuseUneClasseAvecSEtQuantificateurGourmandEtoile()
    {
        String candidat = "(?:Nom\\s*:)\\s*([A-Za-zÀ-ÿ0-9,\\s]*)";

        String repare = service.repairCandidate(candidat);

        assertThat(repare).isEqualTo(
            "(?:Nom\\s*:)\\s*([A-Za-zÀ-ÿ0-9,\\s]*?)(?=\\n\\s*\\S.{0,60}?:|\\n\\s*\\n|$)");
    }

    @Test
    void ajouteUneBorneSeulementSiRienNeSuitLeGroupeDansLeMotif()
    {
        // Contre-exemple : le modèle a DÉJÀ borné lui-même le groupe avec une
        // ancre après (ici un simple \n littéral) -> la borne générique ne doit
        // pas s'ajouter en plus, seul le quantificateur devient paresseux.
        String candidat = "(?:Nom\\s*:)\\s*([A-Za-zÀ-ÿ\\s]+)\\n";

        String repare = service.repairCandidate(candidat);

        assertThat(repare).isEqualTo("(?:Nom\\s*:)\\s*([A-Za-zÀ-ÿ\\s]+?)\\n");
    }

    @Test
    void neDoubleParLeQuantificateurDejaParesseux()
    {
        String candidat = "(?:Nom\\s*:)\\s*([A-Za-zÀ-ÿ\\s]+?)";

        String repare = service.repairCandidate(candidat);

        assertThat(repare).isEqualTo(candidat);
    }

    @Test
    void neTouchePasUneClasseSansSMemeAvecQuantificateurGourmand()
    {
        // [A-Za-z0-9]+ n'a pas \s : pas de risque de traverser un retour à la ligne,
        // rien à réparer.
        String candidat = "(?:FACTURE N°\\s*)([A-Za-z0-9]+)";

        String repare = service.repairCandidate(candidat);

        assertThat(repare).isEqualTo(candidat);
    }

    @Test
    void neTouchePasUnSHorsClasseDeCaracteres()
    {
        // \s+ seul (hors [...]) n'est pas concerné par cette règle précise :
        // ce n'est pas lui qui capture la valeur multi-mots à risque de déborder.
        String candidat = "(?:Bill To:)\\s+([A-Za-z]+)";

        String repare = service.repairCandidate(candidat);

        assertThat(repare).isEqualTo(candidat);
    }

    @Test
    void reparationNeChangePasLeResultatDUnMatchDejaCorrect()
    {
        // Propriété clé : rendre paresseux ne peut que RESTREINDRE la capture,
        // jamais l'étendre — une regex qui matchait déjà juste continue de matcher juste.
        String texte = "Nom : Marie Dupont\nAutre champ : x";
        String candidatGourmand = "(?:Nom\\s*:\\s*)([A-Za-zÀ-ÿ\\s]+)";

        String repare = service.repairCandidate(candidatGourmand);
        var matcher = java.util.regex.Pattern.compile(repare, java.util.regex.Pattern.MULTILINE)
            .matcher(texte);

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1).trim()).isEqualTo("Marie Dupont");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Pattern.MULTILINE : ^/$ ancrés par ligne, pas seulement par texte entier
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void validateRegexAccepteUnAncrageParLigneGraceAMultiline()
    {
        // Motif vu en pratique : le modèle utilise ^/$ en pensant ancrer CHAQUE
        // ligne (comme dans la plupart des langages) — sans MULTILINE, Java
        // n'ancre que le début/fin du texte ENTIER et une telle regex ne
        // matche jamais un champ qui n'est pas sur la toute première/dernière ligne.
        String texte = "INVOICE\nBalance Due: $22.17\nNotes: merci";

        boolean valide = service.validateRegex("^Balance Due: \\$([0-9.]+)$");
        String resultat = service.testRegex("^Balance Due: \\$([0-9.]+)$", texte);

        assertThat(valide).isTrue();
        assertThat(resultat).isEqualTo("Balance Due: $22.17");
    }

    // ─────────────────────────────────────────────────────────────────────
    // validateCandidate : pipeline complet (repair -> validation) sur le cas réel
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void validateCandidateAccepteApresReparationUnCandidatQuiEchouaitAvant()
    {
        String ocrAttestation = "Prénoms et Nom : Lloyd Marvin KOUTELE\n"
            + "Date de naissance : 12/05/2000";
        String candidatBrut = "(?:Prénoms\\s*et\\s*Nom\\s*:)\\s*([A-Za-zÀ-ÿ\\s]+)";

        String resultat = service.validateCandidate(candidatBrut, ocrAttestation, "Lloyd Marvin KOUTELE");

        assertThat(resultat).isNotNull();
        // Vérifie que la regex ACCEPTÉE ne déborde plus sur le champ suivant.
        var matcher = java.util.regex.Pattern.compile(resultat, java.util.regex.Pattern.MULTILINE)
            .matcher(ocrAttestation);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1).trim()).isEqualTo("Lloyd Marvin KOUTELE");
    }
}
