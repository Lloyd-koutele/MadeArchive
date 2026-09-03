package made.archive.service.document;

/**
 * Un mot (ou fragment de texte contigu) reconnu par l'OCR, avec sa position
 * sur la page — indépendant de la source (Tess4J pour un scan/image, PDFBox
 * pour un PDF à couche texte, voir OcrService). Sert de base commune à
 * OcrPositionalExtractionService pour reconstituer les associations
 * libellé → valeur qu'une lecture purement linéaire du texte peut casser
 * (ex : deux colonnes lues bloc par bloc plutôt que ligne par ligne).
 *
 * Coordonnées en pixels (scan) ou en points PDF (PDF à couche texte) — les
 * deux repères ont l'axe Y croissant vers le bas, ce qui suffit pour le
 * regroupement en lignes et la comparaison gauche/droite fait par
 * OcrPositionalExtractionService (pas de mélange des deux dans un même appel).
 */
public record PositionedWord(String texte, int x, int y, int largeur, int hauteur)
{
    public int yCentre()
    {
        return y + hauteur / 2;
    }

    public int xFin()
    {
        return x + largeur;
    }
}
