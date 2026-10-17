package made.archive.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import made.archive.config.TesseractProperties;
import made.archive.repository.OcrResultRepository;
import made.archive.security.DocumentEncryptionService;
import made.archive.service.document.OcrService;
import made.archive.service.document.TesseractDictionaryService;
import made.archive.service.storage.StorageService;

/**
 * Mesure du critère de performance « OCR < 3 s par page (scans de bonne qualité, 300 DPI minimum) » (mémoire §2.4.3).
 *
 * Le test fabrique de VRAIS scans : des pages A4 rendues en image à 300 DPI (2480 × 3508 px, texte français en
 * corps 11 pt), insérées dans un PDF SANS couche texte — le service est donc obligé de rendre la page et d'appeler
 * Tesseract (langues fra+eng+ara, comme en production), exactement comme pour un document numérisé. Il exécute le
 * vrai OcrService.extractTextOnly, mesure le temps par page après un passage de chauffe, vérifie que le texte est
 * correctement reconnu (une mesure de vitesse n'a de sens que si le résultat est juste) et écrit le détail dans
 * build/perf/ocr.json pour être reporté dans le mémoire.
 *
 * Prérequis : Tesseract et ses données de langue (variable d'environnement TESSERACT_DATA_PATH, sinon
 * /opt/homebrew/share/tessdata ou /usr/share/tesseract-ocr/5/tessdata) ; le test est ignoré s'ils sont absents.
 * Seuil réglable : -Dperf.ocr.maxMs=3000 (médiane par page).
 */
@Tag("performance")
class OcrPerformanceTest
{
    private static final int LARGEUR = 2480;     // A4 à 300 DPI
    private static final int HAUTEUR = 3508;
    /** Langues passées à Tesseract : -Dperf.ocr.langues=fra (défaut : celles de la production). */
    private static final String LANGUES = System.getProperty("perf.ocr.langues", "fra+eng+ara");
    private static final int MESURES = 8;
    private static final String[] MOTS_ATTENDUS = {"Polytechnique", "délibération", "Dakar", "semestre"};

    private static final String PARAGRAPHE =
        "Université Cheikh Anta Diop de Dakar. École Supérieure Polytechnique. Bulletin de notes du semestre cinq, "
        + "année académique 2025-2026. Le présent document atteste que l'étudiant a validé l'ensemble des unités "
        + "d'enseignement du semestre avec une moyenne générale de 14,25 sur 20. Les résultats sont arrêtés par le "
        + "jury de délibération réuni le 12 juillet 2026 sous la présidence du chef du département de génie informatique. ";

    private static Path donneesTesseract()
    {
        String env = System.getenv("TESSERACT_DATA_PATH");
        for (String c : new String[] {env, "/opt/homebrew/share/tessdata", "/usr/share/tesseract-ocr/5/tessdata", "/usr/share/tessdata"})
        {
            if (c != null && !c.isBlank() && Files.exists(Path.of(c, "fra.traineddata"))) return Path.of(c);
        }
        return null;
    }

    /** Une page A4 à 300 DPI (texte français en corps 11 pt) ; remplit `dessines` avec les mots réellement posés sur la page. */
    private static BufferedImage page300(List<String> dessines)
    {
        BufferedImage img = new BufferedImage(LARGEUR, HAUTEUR, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, LARGEUR, HAUTEUR);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
        g.setFont(new Font("Serif", Font.PLAIN, 46)); // ≈ 11 pt à 300 DPI
        int y = 220;
        StringBuilder ligne = new StringBuilder();
        List<String> enCours = new ArrayList<>();
        outer:
        for (String mot : (PARAGRAPHE.repeat(7)).split(" "))
        {
            if (g.getFontMetrics().stringWidth(ligne + mot) > LARGEUR - 440)
            {
                g.drawString(ligne.toString(), 220, y);
                dessines.addAll(enCours);
                enCours.clear();
                y += 68;
                ligne.setLength(0);
                if (y > HAUTEUR - 250) break outer;
            }
            ligne.append(mot).append(' ');
            enCours.add(mot);
        }
        g.dispose();
        return img;
    }

    private static byte[] pdfDepuisImages(List<BufferedImage> images) throws Exception
    {
        try (PDDocument doc = new PDDocument())
        {
            for (BufferedImage img : images)
            {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                PDImageXObject image = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page))
                {
                    cs.drawImage(image, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdfScanne(int pages) throws Exception
    {
        List<BufferedImage> images = new ArrayList<>();
        for (int i = 0; i < pages; i++) images.add(page300(new ArrayList<>()));
        return pdfDepuisImages(images);
    }

    /**
     * Simule la même page numérisée à une autre résolution : moyenne des pixels (moins de points pour le même texte),
     * léger flou optique puis bruit de capteur — la dégradation est identique à toutes les résolutions, seule la
     * finesse de l'image change.
     */
    private static BufferedImage numeriserA(BufferedImage ref, int dpi)
    {
        int w = Math.round(LARGEUR * dpi / 300f), h = Math.round(HAUTEUR * dpi / 300f);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        Image reduit = dpi == 300 ? ref : ref.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING);
        g.drawImage(reduit, 0, 0, null);
        g.dispose();
        float[] k = {1 / 16f, 2 / 16f, 1 / 16f, 2 / 16f, 4 / 16f, 2 / 16f, 1 / 16f, 2 / 16f, 1 / 16f};
        BufferedImage flou = new ConvolveOp(new Kernel(3, 3, k), ConvolveOp.EDGE_NO_OP, null).filter(img, null);
        Random bruit = new Random(42);
        int[] px = new int[1];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
            {
                flou.getRaster().getPixel(x, y, px);
                px[0] = (int) Math.max(0, Math.min(255, px[0] + bruit.nextGaussian() * 18));
                flou.getRaster().setPixel(x, y, px);
            }
        return flou;
    }

    private static String normaliser(String m)
    {
        return java.text.Normalizer.normalize(m, java.text.Normalizer.Form.NFC).toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static OcrService serviceOcr() throws Exception
    {
        Path tessdata = donneesTesseract();
        assumeTrue(tessdata != null, "Tesseract et ses données de langue (fra.traineddata) sont absents : mesure ignorée");
        TesseractProperties props = new TesseractProperties();
        props.setDataPath(tessdata.toString());
        props.setLangues(LANGUES);
        props.setOcrViaTika(Boolean.parseBoolean(System.getProperty("perf.ocr.viaTika", "true")));
        props.setCustomDictionaryPath(Files.createTempDirectory("tess-perf").toString());
        return new OcrService(mock(StorageService.class), mock(OcrResultRepository.class), props,
            mock(DocumentEncryptionService.class), mock(TesseractDictionaryService.class));
    }

    private static long mediane(List<Long> v)
    {
        List<Long> t = new ArrayList<>(v);
        Collections.sort(t);
        return t.get(t.size() / 2);
    }

    @Test
    void ocrParPage_scan300Dpi_resteSousLeSeuil() throws Exception
    {
        OcrService ocr = serviceOcr();

        byte[] unePage = pdfScanne(1);
        byte[] cinqPages = pdfScanne(5);

        // Passage de chauffe : chargement des bibliothèques natives et des modèles de langue (non compté).
        String chauffe = ocr.extractTextOnly(unePage, null);
        assertThat(chauffe).as("le texte du scan doit être reconnu").isNotBlank();

        List<Long> msParPage = new ArrayList<>();
        String dernier = null;
        for (int i = 0; i < MESURES; i++)
        {
            long t0 = System.nanoTime();
            dernier = ocr.extractTextOnly(unePage, null);
            msParPage.add((System.nanoTime() - t0) / 1_000_000);
        }
        long t0 = System.nanoTime();
        String texte5 = ocr.extractTextOnly(cinqPages, null);
        long ms5 = (System.nanoTime() - t0) / 1_000_000;

        long min = Collections.min(msParPage), max = Collections.max(msParPage), med = mediane(msParPage);
        double moyenne = msParPage.stream().mapToLong(Long::longValue).average().orElse(0);
        List<Long> tri = new ArrayList<>(msParPage);
        Collections.sort(tri);
        long p95 = tri.get((int) Math.ceil(0.95 * tri.size()) - 1);

        int trouves = 0;
        for (String mot : MOTS_ATTENDUS) if (dernier.contains(mot)) trouves++;

        String machine = System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", "
            + Runtime.getRuntime().availableProcessors() + " cœurs, Java " + System.getProperty("java.version");
        String json = String.format(java.util.Locale.ROOT,
            "{\"critere\":\"OCR par page\",\"resolution_dpi\":300,\"langues\":\"%s\",\"mesures\":%d,"
                + "\"min_ms\":%d,\"mediane_ms\":%d,\"moyenne_ms\":%.0f,\"p95_ms\":%d,\"max_ms\":%d,"
                + "\"cinq_pages_total_ms\":%d,\"cinq_pages_par_page_ms\":%d,\"mots_reconnus\":\"%d/%d\",\"machine\":\"%s\"}",
            LANGUES, MESURES, min, med, moyenne, p95, max, ms5, ms5 / 5, trouves, MOTS_ATTENDUS.length, machine);
        Path sortie = Path.of("build", "perf");
        Files.createDirectories(sortie);
        Files.writeString(sortie.resolve("ocr.json"), json);
        System.out.println("[PERF][OCR] " + json);

        assertThat(trouves).as("mots clés reconnus par l'OCR").isEqualTo(MOTS_ATTENDUS.length);
        assertThat(texte5).as("le document de 5 pages doit être lu en entier").contains("délibération");
        long seuil = Long.getLong("perf.ocr.maxMs", 3000L);
        assertThat(med).as("médiane OCR par page (ms), seuil %d ms", seuil).isLessThan(seuil);
    }

    /**
     * Qualité de reconnaissance selon la résolution du scan — justifie « 300 DPI minimum » : la même page est numérisée
     * (simulation) à 100, 150, 200 et 300 DPI, passée dans le vrai OcrService, et on relève la part des mots posés sur
     * la page qui sont retrouvés dans le texte reconnu. Écrit build/perf/ocr-resolution.json.
     */
    @Test
    void ocrSelonLaResolution_qualiteDuTexteReconnu() throws Exception
    {
        OcrService ocr = serviceOcr();
        List<String> posés = new ArrayList<>();
        BufferedImage ref = page300(posés);
        List<String> reference = new ArrayList<>();
        for (String m : posés) { String n = normaliser(m); if (!n.isBlank()) reference.add(n); }
        ocr.extractTextOnly(pdfDepuisImages(List.of(ref)), null);   // chauffe

        StringBuilder sb = new StringBuilder("{\"critere\":\"Qualité de reconnaissance selon la résolution\",\"mots_reference\":" + reference.size() + ",\"resolutions\":[");
        Map<Integer, Double> qualite = new java.util.LinkedHashMap<>();
        boolean premier = true;
        for (int dpi : new int[] {100, 150, 200, 300})
        {
            String texte = ocr.extractTextOnly(pdfDepuisImages(List.of(numeriserA(ref, dpi))), null);
            Map<String, Integer> lus = new java.util.HashMap<>();
            if (texte != null) for (String m : texte.split("\\s+")) lus.merge(normaliser(m), 1, Integer::sum);
            int retrouves = 0;
            for (String m : reference)
            {
                Integer n = lus.get(m);
                if (n != null && n > 0) { retrouves++; lus.put(m, n - 1); }
            }
            double pct = 100.0 * retrouves / reference.size();
            qualite.put(dpi, pct);
            sb.append(premier ? "" : ",").append(String.format(java.util.Locale.ROOT, "{\"dpi\":%d,\"mots_retrouves\":%d,\"pourcentage\":%.1f}", dpi, retrouves, pct));
            premier = false;
        }
        sb.append("]}");
        Path sortie = Path.of("build", "perf");
        Files.createDirectories(sortie);
        Files.writeString(sortie.resolve("ocr-resolution.json"), sb.toString());
        System.out.println("[PERF][OCR-RESOLUTION] " + sb);

        assertThat(qualite.get(300)).as("part des mots retrouvés à 300 DPI (%)").isGreaterThanOrEqualTo(95.0);
        assertThat(qualite.get(300)).as("300 DPI ne doit pas faire moins bien que 100 DPI").isGreaterThanOrEqualTo(qualite.get(100));
    }
}
