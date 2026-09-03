package made.archive.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.TesseractProperties;
import made.archive.entite.Document;
import made.archive.entite.OcrResult;
import made.archive.entite.OcrStatus;
import made.archive.entite.TypeDocument;
import made.archive.repository.OcrResultRepository;
import made.archive.security.DocumentEncryptionService;
import made.archive.service.storage.StorageService;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.Word;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class OcrService
{
    private final StorageService storageService;
    private final OcrResultRepository ocrResultRepository;
    private final TesseractProperties tesseractProperties;
    private final DocumentEncryptionService documentEncryptionService;
    private final TesseractDictionaryService tesseractDictionaryService;
    private final Tika tika = new Tika();

    private static final String LANGUAGES = "fra+eng+ara";
    private static final int TIKA_MAX_CHARS = -1;
    private static final float PDF_RENDER_DPI = 300f;

    /**
     * PHASE 2 (optimisé) : enregistre un texte OCR DÉJÀ CALCULÉ en Phase 1
     * (sessionData.extractedText) — ne re-télécharge PAS, ne déchiffre PAS,
     * ne relance PAS Tesseract. L'OCR est l'opération la plus coûteuse du
     * pipeline ; la refaire une seconde fois ici (comme le faisait
     * processDocument()) doublait ce coût pour rien, le texte étant déjà
     * disponible depuis la Phase 1.
     *
     * À utiliser à la place de {@link #processDocument(Document)} dès que le
     * texte a déjà été extrait plus tôt dans le même flux d'upload.
     */
    public String recordExtractedText(Document document, String extractedText)
    {
        OcrResult result = new OcrResult();
        result.setDocument(document);
        result.setProcessedAt(LocalDateTime.now());
        result.setLanguage(LANGUAGES);

        if (extractedText == null || extractedText.isBlank())
        {
            result.setStatus(OcrStatus.SKIPPED);
            result.setTextStorageKey(null);
        }
        else
        {
            String textKey = storeOcrText(document, extractedText);
            result.setStatus(OcrStatus.SUCCESS);
            result.setTextStorageKey(textKey);
        }

        ocrResultRepository.save(result);
        return extractedText;
    }

    /**
     * PHASE 2 (fallback) : traitement complet quand le texte n'a PAS déjà été
     * extrait ailleurs — télécharge, déchiffre et OCRise depuis MinIO.
     * Coûteux (voir {@link #recordExtractedText}) : à réserver aux cas où on
     * ne dispose vraiment pas du texte en amont (ex : re-traitement a posteriori).
     * Crée un enregistrement OcrResult.
     */
    public String processDocument(Document document)
    {
        OcrResult result = new OcrResult();
        result.setDocument(document);
        result.setProcessedAt(LocalDateTime.now());
        result.setLanguage(LANGUAGES);

        String extractedText = null;

        try (InputStream inputStream = storageService.download(document.getStorageKey()))
        {
            // Le PDF/A est chiffré au repos (AES-256-GCM) — déchiffrement
            // immédiat après lecture, avant toute détection MIME/extraction.
            byte[] fileBytes = documentEncryptionService.decrypt(inputStream.readAllBytes());
            String mimeType = tika.detect(fileBytes);
            log.info("[OCR] Type détecté : {} pour {}", mimeType, document.getStorageKey());

            extractedText = extractText(fileBytes, mimeType, document.getTypeDocument());

            if (extractedText == null || extractedText.isBlank())
            {
                result.setStatus(OcrStatus.SKIPPED);
                result.setTextStorageKey(null);
            }
            else
            {
                String textKey = storeOcrText(document, extractedText);
                result.setStatus(OcrStatus.SUCCESS);
                result.setTextStorageKey(textKey);
            }
        }
        catch (Exception e)
        {
            log.error("[OCR] Échec pour document {} : {}", document.getId(), e.getMessage());
            result.setStatus(OcrStatus.FAILED);
            result.setTextStorageKey(null);
        }

        ocrResultRepository.save(result);
        return extractedText;
    }

    /**
     * PHASE 1 : Extraction de texte UNIQUEMENT (pas de Document créé)
     * Utilisé pour le preview OCR avant validation.
     *
     * @deprecated Préférer {@link #extractTextOnly(byte[], TypeDocument)} qui
     *             guide Tesseract avec le vocabulaire du type de document.
     */
    @Deprecated
    public String extractTextOnly(byte[] pdfABytes)
    {
        return extractTextOnly(pdfABytes, null);
    }

    /**
     * PHASE 1 : Extraction de texte UNIQUEMENT (pas de Document créé)
     * Utilisé pour le preview OCR avant validation.
     *
     * @param typeDocument type du document en cours de dépôt — utilisé pour
     *                     guider Tesseract avec un dictionnaire "user-words"
     *                     (noms d'attributs + valeurs déjà confirmées pour ce
     *                     type). Peut être null (fallback sans guidage).
     */
    public String extractTextOnly(byte[] pdfABytes, TypeDocument typeDocument)
    {
        try
        {
            String mimeType = tika.detect(pdfABytes);
            log.info("[OCR-Phase1] Type détecté : application/pdf");
            return extractText(pdfABytes, mimeType, typeDocument);
        }
        catch (Exception e)
        {
            log.error("[OCR-Phase1] Échec extraction : {}", e.getMessage());
            return null;
        }
    }

    /**
     * Texte + position de chaque mot reconnu sur la (première) page — voir
     * OcrPositionalExtractionService. `mots` est vide si la position n'a pas pu
     * être déterminée (Word/Excel sans mise en page 2D pertinente, échec
     * best-effort) : le texte reste utilisable normalement, seule l'extraction
     * positionnelle est indisponible pour ce document.
     */
    public record OcrExtractionResult(String texte, List<PositionedWord> mots) {}

    /**
     * PHASE 1 (positionnel) : comme {@link #extractTextOnly(byte[], TypeDocument)}
     * mais conserve aussi la position de chaque mot reconnu, pour permettre à
     * OcrPositionalExtractionService de retrouver un champ par son libellé même
     * quand la linéarisation du texte a éloigné le libellé de sa valeur (mise en
     * page en colonnes, formulaire...).
     */
    public OcrExtractionResult extractWithPositions(byte[] pdfABytes, TypeDocument typeDocument)
    {
        try
        {
            String mimeType = tika.detect(pdfABytes);
            return extractTextAndPositions(pdfABytes, mimeType, typeDocument);
        }
        catch (Exception e)
        {
            log.error("[OCR-Phase1] Échec extraction positionnelle : {}", e.getMessage());
            return new OcrExtractionResult(null, List.of());
        }
    }

    private OcrExtractionResult extractTextAndPositions(byte[] fileBytes, String mimeType, TypeDocument typeDocument)
            throws IOException, SAXException, TikaException, TesseractException
    {
        // Images → Tess4J directement
        if (mimeType.startsWith("image/"))
        {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(fileBytes));
            if (image == null)
            {
                log.warn("[OCR] ImageIO ne peut pas lire ce fichier pour Tess4J");
                return new OcrExtractionResult(null, List.of());
            }
            Tesseract tesseract = buildTesseract(typeDocument);
            String texte = tesseract.doOCR(image);
            return new OcrExtractionResult(texte, motsDepuisTesseract(tesseract, image));
        }

        // PDF → couche texte (PDFBox/Tika) si elle existe, sinon rendu + Tess4J
        if (mimeType.equals("application/pdf"))
        {
            String texteTika = extractWithTika(fileBytes);
            if (texteTika != null && !texteTika.isBlank())
            {
                return new OcrExtractionResult(texteTika, extrairePositionsPdfBox(fileBytes));
            }
            log.info("[OCR] PDF sans couche texte, rendu page par page (positionnel)...");
            return extractPdfWithTesseractPositions(fileBytes, typeDocument);
        }

        // Word, Excel, etc. → Tika, sans position (pas de mise en page 2D pertinente ici)
        String texte = extractWithTika(fileBytes);
        if (texte != null && !texte.isBlank())
        {
            return new OcrExtractionResult(texte, List.of());
        }

        // Fallback Tess4J pour tout autre format non reconnu par Tika
        log.info("[OCR] Tika vide, fallback Tess4J (positionnel)...");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(fileBytes));
        if (image == null)
        {
            return new OcrExtractionResult(null, List.of());
        }
        Tesseract tesseract = buildTesseract(typeDocument);
        String texteOcr = tesseract.doOCR(image);
        return new OcrExtractionResult(texteOcr, motsDepuisTesseract(tesseract, image));
    }

    /**
     * Rendu + OCR page par page, comme {@link #extractPdfWithTesseract} — mais
     * ne récupère les positions que de la PREMIÈRE page : les métadonnées d'un
     * document (facture, identité, certificat...) s'y trouvent presque
     * toujours, et ça évite de mélanger les coordonnées de pages différentes
     * (chaque page repart de (0,0), un même Y désignerait sinon deux endroits
     * différents selon la page).
     */
    private OcrExtractionResult extractPdfWithTesseractPositions(byte[] fileBytes, TypeDocument typeDocument)
            throws IOException, TesseractException
    {
        Tesseract tesseract = buildTesseract(typeDocument);
        StringBuilder fullText = new StringBuilder();
        List<PositionedWord> motsPremierePage = List.of();

        try (PDDocument pdDocument = PDDocument.load(new ByteArrayInputStream(fileBytes).readAllBytes()))
        {
            PDFRenderer renderer = new PDFRenderer(pdDocument);
            int pageCount = pdDocument.getNumberOfPages();

            for (int page = 0; page < pageCount; page++)
            {
                BufferedImage image = renderer.renderImageWithDPI(page, PDF_RENDER_DPI);
                String pageText = tesseract.doOCR(image);
                if (pageText != null && !pageText.isBlank())
                {
                    fullText.append(pageText).append("\n");
                }
                if (page == 0)
                {
                    motsPremierePage = motsDepuisTesseract(tesseract, image);
                }
            }
        }

        return new OcrExtractionResult(fullText.toString().trim(), motsPremierePage);
    }

    /** Mots + positions reconnus par Tess4J sur une image déjà OCRisée par `tesseract.doOCR`. */
    private List<PositionedWord> motsDepuisTesseract(Tesseract tesseract, BufferedImage image)
    {
        try
        {
            List<Word> mots = tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
            List<PositionedWord> resultat = new ArrayList<>();
            for (Word mot : mots)
            {
                if (mot.getText() == null || mot.getText().isBlank())
                {
                    continue;
                }
                Rectangle r = mot.getBoundingBox();
                resultat.add(new PositionedWord(mot.getText().trim(), r.x, r.y, r.width, r.height));
            }
            return resultat;
        }
        catch (Exception e)
        {
            log.warn("[OCR] Extraction positionnelle Tess4J échouée (best-effort) : {}", e.getMessage());
            return List.of();
        }
    }

    /** Mots + positions extraits par PDFBox sur la première page d'un PDF à couche texte. */
    private List<PositionedWord> extrairePositionsPdfBox(byte[] fileBytes)
    {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(fileBytes)))
        {
            PositionalTextStripper stripper = new PositionalTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            stripper.getText(document); // déclenche writeString(...) ; la chaîne renvoyée n'est pas utilisée ici
            return stripper.getMots();
        }
        catch (Exception e)
        {
            log.warn("[OCR] Extraction positionnelle PDFBox échouée (best-effort) : {}", e.getMessage());
            return List.of();
        }
    }

    /** Capture la position (x, y, largeur, hauteur) de chaque fragment de texte pendant l'extraction PDFBox. */
    private static class PositionalTextStripper extends PDFTextStripper
    {
        private final List<PositionedWord> mots = new ArrayList<>();

        PositionalTextStripper() throws IOException { super(); }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException
        {
            if (text == null || text.isBlank() || textPositions == null || textPositions.isEmpty())
            {
                return;
            }
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (TextPosition tp : textPositions)
            {
                minX = Math.min(minX, tp.getXDirAdj());
                minY = Math.min(minY, tp.getYDirAdj() - tp.getHeightDir());
                maxX = Math.max(maxX, tp.getXDirAdj() + tp.getWidthDirAdj());
                maxY = Math.max(maxY, tp.getYDirAdj());
            }
            mots.add(new PositionedWord(text.trim(),
                Math.round(minX), Math.round(minY),
                Math.round(maxX - minX), Math.round(maxY - minY)));
        }

        List<PositionedWord> getMots() { return mots; }
    }

    private String extractText(byte[] fileBytes, String mimeType, TypeDocument typeDocument)
            throws IOException, SAXException, TikaException, TesseractException
    {
        // Images → Tess4J directement
        if (mimeType.startsWith("image/"))
        {
            return extractWithTesseract(fileBytes, typeDocument);
        }

        // PDF → tentative Tika d'abord
        if (mimeType.equals("application/pdf"))
        {
            String text = extractWithTika(fileBytes);
            if (text != null && !text.isBlank())
            {
                return text;
            }
            // PDF scanné → rendu page par page via PDFBox + Tess4J
            log.info("[OCR] PDF sans couche texte, rendu page par page...");
            return extractPdfWithTesseract(fileBytes, typeDocument);
        }

        // Word, Excel, etc. → Tika
        String text = extractWithTika(fileBytes);
        if (text != null && !text.isBlank())
        {
            return text;
        }

        // Fallback Tess4J pour tout autre format non reconnu par Tika
        log.info("[OCR] Tika vide, fallback Tess4J...");
        return extractWithTesseract(fileBytes, typeDocument);
    }

    private String extractWithTika(byte[] fileBytes)
            throws IOException, SAXException, TikaException
    {
        BodyContentHandler handler = new BodyContentHandler(TIKA_MAX_CHARS);
        AutoDetectParser parser = new AutoDetectParser();
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        parser.parse(new ByteArrayInputStream(fileBytes), handler, metadata, context);
        return handler.toString();
    }

    private String extractWithTesseract(byte[] fileBytes, TypeDocument typeDocument)
            throws IOException, TesseractException
    {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(fileBytes));
        if (image == null)
        {
            log.warn("[OCR] ImageIO ne peut pas lire ce fichier pour Tess4J");
            return null;
        }
        return buildTesseract(typeDocument).doOCR(image);
    }

    private String extractPdfWithTesseract(byte[] fileBytes, TypeDocument typeDocument)
            throws IOException, TesseractException
    {
        Tesseract tesseract = buildTesseract(typeDocument);
        StringBuilder fullText = new StringBuilder();

        try (PDDocument pdDocument = PDDocument.load(new ByteArrayInputStream(fileBytes).readAllBytes()))
        {
            PDFRenderer renderer = new PDFRenderer(pdDocument);
            int pageCount = pdDocument.getNumberOfPages();
            log.info("[OCR] PDF scanné : {} page(s) à traiter", pageCount);

            for (int page = 0; page < pageCount; page++)
            {
                BufferedImage image = renderer.renderImageWithDPI(page, PDF_RENDER_DPI);
                String pageText = tesseract.doOCR(image);
                if (pageText != null && !pageText.isBlank())
                {
                    fullText.append(pageText).append("\n");
                }
                log.info("[OCR] Page {}/{} traitée", page + 1, pageCount);
            }
        }

        return fullText.toString().trim();
    }

    /**
     * @param typeDocument si fourni, Tesseract est pointé vers un répertoire
     *                     tessdata isolé pour ce type (voir
     *                     TesseractDictionaryService), contenant un
     *                     dictionnaire "user-words" (attributs du type +
     *                     valeurs déjà confirmées) qui biaise la
     *                     reconnaissance vers ce vocabulaire. Fallback
     *                     silencieux sur le datapath global si null ou en
     *                     cas d'échec de préparation.
     */
    private Tesseract buildTesseract(TypeDocument typeDocument)
    {
        String datapath = typeDocument != null
            ? tesseractDictionaryService.ensureDictionary(typeDocument)
            : tesseractProperties.getDataPath();

        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(datapath);
        tesseract.setLanguage(LANGUAGES);
        tesseract.setVariable("user_words_suffix", "user-words");
        tesseract.setVariable("load_system_dawg", "1");
        tesseract.setVariable("load_freq_dawg", "1");
        return tesseract;
    }

    private String storeOcrText(Document document, String text)
    {
        try
        {
            String key = "ocr/" + document.getId().toString() + ".txt";
            byte[] textBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            storageService.uploadBytes(textBytes, key, "text/plain");
            log.info("[OCR] Texte stocké : {}", key);
            return key;
        }
        catch (Exception e)
        {
            log.error("[OCR] Impossible de stocker le texte OCR : {}", e.getMessage());
            return null;
        }
    }
}