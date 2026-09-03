package made.archive.service.document;

import lombok.extern.slf4j.Slf4j;
import made.archive.entite.MetaData;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extraction de métadonnées guidée par la POSITION du texte reconnu, plutôt
 * que par la regex apprise sur un document précédent (voir OllamaService /
 * RegexGenerationService).
 *
 * Le texte OCR linéaire (une simple chaîne) perd la disposition visuelle du
 * document : deux colonnes lues bloc par bloc plutôt que ligne par ligne
 * finissent par éloigner un libellé de sa valeur dans le texte final, même
 * quand ils sont visuellement côte à côte sur le document — aucune regex ne
 * peut alors les réassocier, l'information de proximité est déjà perdue.
 *
 * Ici, on reconstruit les LIGNES à partir des coordonnées de chaque mot (peu
 * importe l'ordre dans lequel l'OCR/PDFBox les a produits), puis pour chaque
 * champ de métadonnée du type de document, on cherche son propre NOM comme
 * libellé sur une ligne, et on prend la valeur juste à droite (même ligne)
 * ou juste en dessous (libellé et valeur empilés). Fonctionne dès le premier
 * document — pas besoin d'un exemple préalable comme pour la regex.
 *
 * Complément de la regex, pas un remplacement : ne trouve rien pour un champ
 * sans libellé visible proche de sa valeur (ex. un numéro de série isolé) —
 * ces cas restent couverts par OllamaService/RegexGenerationService.
 */
@Slf4j
@Service
public class OcrPositionalExtractionService
{
    public Map<String, String> extraire(List<PositionedWord> mots, List<MetaData> metaDataList)
    {
        if (mots == null || mots.isEmpty() || metaDataList == null || metaDataList.isEmpty())
        {
            return Map.of();
        }

        List<List<PositionedWord>> lignes = regrouperEnLignes(mots);
        Map<String, String> resultats = new LinkedHashMap<>();

        // Tous les AUTRES libellés du type — sert à ne jamais confondre la valeur
        // d'un champ avec le libellé d'un champ voisin (ex : "Bill To:" et "Ship
        // To:" côte à côte sur la même ligne, voir chercherValeurPourLabel).
        Set<String> tousLesLabels = metaDataList.stream()
            .map(m -> normaliser(m.getNom()))
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());

        for (MetaData champ : metaDataList)
        {
            String label = normaliser(champ.getNom());
            if (label.isBlank())
            {
                continue;
            }

            Set<String> autresLabels = tousLesLabels.stream()
                .filter(l -> !l.equals(label))
                .collect(Collectors.toSet());

            String valeur = chercherValeurPourLabel(lignes, label, autresLabels);
            if (valeur != null && !valeur.isBlank())
            {
                resultats.put(champ.getNom(), valeur);
                log.debug("[Ocr-Positionnel] '{}' → '{}'", champ.getNom(), valeur);
            }
        }

        return resultats;
    }

    // ═══════════════════════════════════════════════════════════════
    // Regroupement en lignes à partir des coordonnées
    // ═══════════════════════════════════════════════════════════════

    /**
     * Regroupe les mots par bande verticale (même ligne visuelle), triés de
     * haut en bas puis, à l'intérieur d'une ligne, de gauche à droite —
     * indépendamment de l'ordre dans lequel la source (Tess4J/PDFBox) les a
     * produits. Tolérance calculée à partir de la hauteur de chaque mot pour
     * s'adapter aux deux repères de coordonnées possibles (pixels d'un scan,
     * points d'un PDF à couche texte).
     */
    private List<List<PositionedWord>> regrouperEnLignes(List<PositionedWord> mots)
    {
        List<PositionedWord> tries = mots.stream()
            .sorted(Comparator.comparingInt(PositionedWord::yCentre))
            .toList();

        List<List<PositionedWord>> lignes = new ArrayList<>();
        List<PositionedWord> ligneCourante = new ArrayList<>();
        int yCentreLigne = 0;

        for (PositionedWord mot : tries)
        {
            if (ligneCourante.isEmpty())
            {
                ligneCourante.add(mot);
                yCentreLigne = mot.yCentre();
                continue;
            }

            // Volontairement serré : le jitter de baseline DANS une même ligne (quelques
            // pixels) doit passer, mais deux lignes de texte simple-interligne réellement
            // distinctes (souvent espacées de près d'une hauteur de mot complète) doivent
            // rester séparées — une tolérance trop large les fusionnerait en une seule
            // "ligne", collant par erreur la valeur d'un champ suivant à la précédente.
            int tolerance = Math.max(3, (int) (mot.hauteur() * 0.3));
            if (Math.abs(mot.yCentre() - yCentreLigne) <= tolerance)
            {
                ligneCourante.add(mot);
                // Moyenne glissante simple — suit une légère dérive le long de la ligne
                // sans laisser un mot isolé et mal positionné faire dériver tout le reste.
                yCentreLigne = (yCentreLigne * (ligneCourante.size() - 1) + mot.yCentre()) / ligneCourante.size();
            }
            else
            {
                lignes.add(trierParX(ligneCourante));
                ligneCourante = new ArrayList<>();
                ligneCourante.add(mot);
                yCentreLigne = mot.yCentre();
            }
        }
        if (!ligneCourante.isEmpty())
        {
            lignes.add(trierParX(ligneCourante));
        }

        return lignes;
    }

    private List<PositionedWord> trierParX(List<PositionedWord> ligne)
    {
        return ligne.stream()
            .sorted(Comparator.comparingInt(PositionedWord::x))
            .toList();
    }

    // ═══════════════════════════════════════════════════════════════
    // Recherche libellé → valeur
    // ═══════════════════════════════════════════════════════════════

    /** Une ligne, avec son texte normalisé concaténé et l'offset de chaque mot dans ce texte. */
    private record LigneIndexee(String texteNormalise, List<PositionedWord> mots, int[] debutsMots) {}

    /** Lignes à sonder sous un libellé avant d'abandonner — au-delà, ce n'est plus "juste en dessous". */
    private static final int MAX_LIGNES_EN_DESSOUS = 6;

    /** Tolérance horizontale (mêmes unités que les coordonnées) pour juger "même colonne". */
    private static final int TOLERANCE_COLONNE = 15;

    private String chercherValeurPourLabel(List<List<PositionedWord>> lignes, String label, Set<String> autresLabels)
    {
        for (int i = 0; i < lignes.size(); i++)
        {
            List<PositionedWord> ligne = lignes.get(i);
            LigneIndexee indexee = indexerLigne(ligne);
            int debut = indexee.texteNormalise().indexOf(label);
            if (debut < 0)
            {
                continue;
            }

            int finLabel = debut + label.length();

            // a) reste de la même ligne, à droite du libellé — MAIS pas si c'est en
            //    fait le libellé d'un AUTRE champ juste à côté (ex : "Bill To:" et
            //    "Ship To:" côte à côte sur la même ligne d'en-tête).
            String aDroite = texteApres(indexee, finLabel);
            if (aDroite != null && !aDroite.isBlank())
            {
                String aDroiteNorm = normaliser(aDroite);
                if (!aDroiteNorm.equals(label) && !autresLabels.contains(aDroiteNorm))
                {
                    return aDroite;
                }
            }

            // b) sinon, valeur empilée EN DESSOUS, DANS LA MÊME COLONNE — pas
            //    forcément la ligne suivante : une autre colonne peut intercaler ses
            //    propres lignes entre le libellé et sa valeur empilée (ex : "Ship
            //    Mode:" apparaît entre "Ship To:" et son adresse sur certains
            //    gabarits). On cherche la première ligne, parmi les suivantes, qui a
            //    du texte à peu près à la même position X que le libellé lui-même.
            int xLabelDebut = xDebutDuLabel(indexee, debut);
            String enDessous = chercherEnDessousMemeColonne(lignes, i + 1, xLabelDebut, autresLabels);
            if (enDessous != null && !enDessous.isBlank())
            {
                return enDessous;
            }

            // Libellé trouvé mais rien d'exploitable à proximité — inutile de chercher
            // une deuxième occurrence, la première est la bonne dans l'immense majorité
            // des documents (un libellé n'apparaît normalement qu'une fois).
            return null;
        }

        return null;
    }

    /** Position X du premier mot du libellé — sert d'ancre de colonne pour la recherche en dessous. */
    private int xDebutDuLabel(LigneIndexee indexee, int debutNormalise)
    {
        for (int i = 0; i < indexee.mots().size(); i++)
        {
            if (indexee.debutsMots()[i] >= debutNormalise)
            {
                return indexee.mots().get(i).x();
            }
        }
        return indexee.mots().isEmpty() ? 0 : indexee.mots().get(0).x();
    }

    /**
     * Parcourt les lignes suivantes (bornées à MAX_LIGNES_EN_DESSOUS) à la recherche
     * de la première qui contient du texte proche de `xColonne` — la valeur empilée
     * sous le libellé, quelles que soient les lignes d'AUTRES colonnes intercalées
     * entre les deux. Ne prend, sur la ligne trouvée, que les mots de cette colonne
     * précise (pas toute la ligne, qui peut contenir d'autres colonnes à droite).
     */
    private String chercherEnDessousMemeColonne(List<List<PositionedWord>> lignes, int depuis,
                                                  int xColonne, Set<String> autresLabels)
    {
        int limite = Math.min(lignes.size(), depuis + MAX_LIGNES_EN_DESSOUS);

        for (int i = depuis; i < limite; i++)
        {
            List<PositionedWord> ligne = lignes.get(i);

            int indexAncre = -1;
            for (int j = 0; j < ligne.size(); j++)
            {
                if (Math.abs(ligne.get(j).x() - xColonne) <= TOLERANCE_COLONNE)
                {
                    indexAncre = j;
                    break;
                }
            }
            if (indexAncre < 0)
            {
                continue; // rien dans cette colonne sur cette ligne — une autre colonne l'occupe, on descend encore
            }

            // Prend le mot ancre et tout ce qui le suit SANS grand saut horizontal —
            // un grand saut signale une colonne différente plus loin sur la même ligne.
            List<String> motsValeur = new ArrayList<>();
            int xFinPrecedent = -1;
            for (int j = indexAncre; j < ligne.size(); j++)
            {
                PositionedWord mot = ligne.get(j);
                if (xFinPrecedent >= 0 && mot.x() - xFinPrecedent > 60)
                {
                    break;
                }
                motsValeur.add(mot.texte());
                xFinPrecedent = mot.xFin();
            }

            String valeur = String.join(" ", motsValeur).trim();
            if (!valeur.isBlank() && !autresLabels.contains(normaliser(valeur)))
            {
                return valeur;
            }

            return null; // colonne trouvée mais contenu inexploitable — pas la peine de chercher plus bas
        }

        return null;
    }

    private LigneIndexee indexerLigne(List<PositionedWord> ligne)
    {
        StringBuilder texte = new StringBuilder();
        int[] debuts = new int[ligne.size()];

        for (int i = 0; i < ligne.size(); i++)
        {
            if (i > 0)
            {
                texte.append(' ');
            }
            debuts[i] = texte.length();
            texte.append(normaliser(ligne.get(i).texte()));
        }

        return new LigneIndexee(texte.toString(), ligne, debuts);
    }

    /** Texte ORIGINAL (pas normalisé) des mots dont le début se situe après la fin du libellé. */
    private String texteApres(LigneIndexee indexee, int finLabel)
    {
        List<String> motsRestants = new ArrayList<>();
        for (int i = 0; i < indexee.mots().size(); i++)
        {
            if (indexee.debutsMots()[i] >= finLabel)
            {
                motsRestants.add(indexee.mots().get(i).texte());
            }
        }
        return String.join(" ", motsRestants).trim();
    }

    private String normaliser(String s)
    {
        if (s == null)
        {
            return "";
        }
        return s.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[:;,.]+$", "")
            .replaceAll("\\s+", " ");
    }
}
