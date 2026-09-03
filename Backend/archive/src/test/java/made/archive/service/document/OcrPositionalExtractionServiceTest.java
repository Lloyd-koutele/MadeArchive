package made.archive.service.document;

import made.archive.entite.MetaData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Cas synthétique reproduisant la facture réelle qui a motivé cette
 * fonctionnalité : "Bill To" en libellé au-dessus de sa valeur (empilés),
 * "Date"/"Balance Due" en libellé suivi de sa valeur SUR LA MÊME LIGNE, à
 * droite — mais dont la lecture linéaire de l'OCR avait mélangé l'ordre
 * (toutes les valeurs de la colonne de droite sortaient avant leurs libellés
 * respectifs). Les coordonnées ci-dessous reflètent la position RÉELLE sur
 * la page, indépendamment de l'ordre dans lequel elles apparaîtraient dans
 * un texte OCR linéaire.
 */
class OcrPositionalExtractionServiceTest
{
    private final OcrPositionalExtractionService service = new OcrPositionalExtractionService();

    @Test
    void retrouveLesValeursMalgreUneMiseEnPageEnColonnes()
    {
        List<PositionedWord> mots = List.of(
            // "Bill To:" — libellé seul sur sa ligne, valeur empilée en dessous
            new PositionedWord("Bill", 50, 200, 40, 18),
            new PositionedWord("To:", 95, 200, 30, 18),
            new PositionedWord("Aaron", 50, 230, 50, 18),
            new PositionedWord("Hawkins", 105, 230, 65, 18),

            // "Date:" — libellé et valeur sur la MÊME ligne, à droite
            new PositionedWord("Date:", 400, 180, 45, 18),
            new PositionedWord("May", 470, 180, 35, 18),
            new PositionedWord("12", 510, 180, 20, 18),
            new PositionedWord("2012", 535, 180, 40, 18),

            // "Balance Due:" — idem, une ligne plus bas
            new PositionedWord("Balance", 400, 240, 60, 18),
            new PositionedWord("Due:", 465, 240, 35, 18),
            new PositionedWord("$17.15", 505, 240, 55, 18)
        );

        List<MetaData> champs = List.of(
            champ("Bill to"),
            champ("Date"),
            champ("Balance Due"),
            champ("Titre") // aucun libellé "Titre" présent — doit rester absent du résultat
        );

        Map<String, String> resultats = service.extraire(mots, champs);

        assertEquals("Aaron Hawkins", resultats.get("Bill to"));
        assertEquals("May 12 2012", resultats.get("Date"));
        assertEquals("$17.15", resultats.get("Balance Due"));
        assertFalse(resultats.containsKey("Titre"));
    }

    @Test
    void insensibleAlOrdreDeLecture()
    {
        // Même mots que le cas ci-dessus, mais mélangés — reproduit EXACTEMENT ce qui
        // a motivé cette fonctionnalité : l'OCR/PDFBox les avait rendus dans un ordre
        // de lecture qui décrochait les valeurs de leurs libellés. Le résultat doit
        // être identique, puisque l'algorithme ne se fie qu'aux coordonnées, jamais
        // à l'ordre du texte linéaire.
        List<PositionedWord> mots = new ArrayList<>(List.of(
            new PositionedWord("2012", 535, 180, 40, 18),
            new PositionedWord("$17.15", 505, 240, 55, 18),
            new PositionedWord("Aaron", 50, 230, 50, 18),
            new PositionedWord("Date:", 400, 180, 45, 18),
            new PositionedWord("Due:", 465, 240, 35, 18),
            new PositionedWord("Bill", 50, 200, 40, 18),
            new PositionedWord("Balance", 400, 240, 60, 18),
            new PositionedWord("12", 510, 180, 20, 18),
            new PositionedWord("To:", 95, 200, 30, 18),
            new PositionedWord("May", 470, 180, 35, 18),
            new PositionedWord("Hawkins", 105, 230, 65, 18)
        ));
        Collections.shuffle(mots);

        Map<String, String> resultats = service.extraire(mots, List.of(champ("Bill to"), champ("Date"), champ("Balance Due")));

        assertEquals("Aaron Hawkins", resultats.get("Bill to"));
        assertEquals("May 12 2012", resultats.get("Date"));
        assertEquals("$17.15", resultats.get("Balance Due"));
    }

    @Test
    void neConfondPasUnLibelleVoisinNiUneColonneIntercalee()
    {
        // Coordonnées reprises telles quelles d'une vraie facture (diagnostic du
        // 2026-08-28) : "Bill To:" et "Ship To:" sont côte à côte sur la MÊME ligne
        // (pas un cas "libellé seul puis valeur en dessous"), et "Ship Mode:" —
        // une colonne totalement différente — s'intercale entre "Ship To:" et sa
        // propre valeur, une ligne plus bas. Deux pièges distincts :
        //  1. "Bill to" ne doit pas attraper "Ship To:" (le libellé voisin) comme
        //     si c'était sa valeur, juste parce qu'il est à droite sur la ligne.
        //  2. "Ship to" ne doit pas attraper "Ship Mode: First Class" (la colonne
        //     intercalée) en descendant à l'aveugle sur "la ligne suivante" — il
        //     doit continuer à chercher jusqu'à retrouver sa PROPRE colonne (même X).
        List<PositionedWord> mots = List.of(
            new PositionedWord("Bill", 48, 125, 20, 7),
            new PositionedWord("To:", 70, 125, 10, 7),
            new PositionedWord("Ship", 182, 125, 20, 7),
            new PositionedWord("To:", 204, 125, 14, 7),

            new PositionedWord("Ship", 413, 133, 20, 7),
            new PositionedWord("Mode:", 435, 133, 27, 7),
            new PositionedWord("First", 522, 133, 20, 7),
            new PositionedWord("Class", 544, 133, 24, 7),

            new PositionedWord("Aaron", 48, 140, 33, 7),
            new PositionedWord("Hawkins", 83, 140, 35, 7),
            new PositionedWord("Saltillo,", 182, 140, 40, 7),
            new PositionedWord("Coahuila,", 224, 140, 39, 7)
        );

        Map<String, String> resultats = service.extraire(
            mots, List.of(champ("Bill to"), champ("Ship to")));

        assertEquals("Aaron Hawkins", resultats.get("Bill to"));
        assertEquals("Saltillo, Coahuila,", resultats.get("Ship to"));
    }

    private MetaData champ(String nom)
    {
        MetaData m = new MetaData();
        m.setNom(nom);
        return m;
    }
}
