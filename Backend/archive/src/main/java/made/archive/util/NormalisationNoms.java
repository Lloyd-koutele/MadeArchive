package made.archive.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalisation partagée pour la détection de doublons de noms (UO, Dossiers,
 * emplacements physiques — voir UniteOrganisationnelleService/DossierService/
 * PhysicalLocationService) : deux noms qui ne diffèrent QUE par la casse, les
 * accents, ou l'espacement sont considérés comme LE MÊME nom.
 *
 * "Dossier 4", "dossier4", "  DOSSIER   4  ", "Dôssier 4" normalisent tous
 * vers "dossier4" — les espaces ne sont pas seulement réduits à un seul,
 * ils sont intégralement retirés (contrairement au normalize() habituel de
 * ce code, ex. RegexGenerationService, qui ne fait que réduire les espaces
 * multiples — ici "Dossier 4" et "Dossier4" doivent être le MÊME nom, pas
 * juste "Dossier  4" et "Dossier 4").
 *
 * NE SERT QU'À LA COMPARAISON — le nom saisi par l'utilisateur reste stocké
 * et affiché tel quel (accents/casse/espacement conservés) ; seule cette
 * fonction transforme une COPIE pour la vérification de doublon.
 *
 * Réutilisée aussi par DocumentOcrService pour normaliser le TEXTE OCR complet
 * d'un document avant hachage (voir Document.texteNormaliseSha256) — même
 * principe malgré la taille bien plus grande de l'entrée : seul le résultat
 * (haché ensuite) compte, jamais affiché tel quel, donc aucune perte de
 * lisibilité à retirer intégralement les espaces plutôt que les réduire.
 */
public final class NormalisationNoms
{
    private NormalisationNoms() {}

    /** \p{M} = tout caractère de la catégorie Unicode "Mark" — couvre les diacritiques
     *  combinants produits par la décomposition NFD (ex. "é" -> "e" + accent aigu combinant). */
    private static final Pattern DIACRITIQUES = Pattern.compile("\\p{M}");
    private static final Pattern ESPACES = Pattern.compile("\\s+");

    public static String normaliser(String texte)
    {
        if (texte == null)
        {
            return "";
        }
        String sansAccents = DIACRITIQUES.matcher(Normalizer.normalize(texte, Normalizer.Form.NFD)).replaceAll("");
        return ESPACES.matcher(sansAccents.toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
