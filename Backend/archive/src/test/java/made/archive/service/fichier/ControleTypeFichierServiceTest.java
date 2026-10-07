package made.archive.service.fichier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import made.archive.entite.AuditAction;
import made.archive.exception.BusinessException;
import made.archive.service.audit.AuditLogService;

/**
 * Le type RÉEL d'un fichier (lu dans son contenu) l'emporte toujours sur son nom : les fichiers sont fabriqués ici avec
 * de vraies bibliothèques (PDFBox, POI, ImageIO), pas des suites d'octets inventées, puis renommés pour mentir.
 */
@Tag("unit")
class ControleTypeFichierServiceTest
{
    private AuditLogService audit;
    private ControleTypeFichierService service;

    @BeforeEach
    void init()
    {
        audit = mock(AuditLogService.class);
        service = new ControleTypeFichierService(audit);
    }

    // ── fabrication de vrais fichiers ───────────────────────────────────────────────────────────────────────

    private static byte[] pdf() throws Exception
    {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream o = new ByteArrayOutputStream())
        {
            d.addPage(new PDPage());
            d.save(o);
            return o.toByteArray();
        }
    }

    private static byte[] image(String format, int taille) throws Exception
    {
        BufferedImage img = new BufferedImage(taille, taille, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, format, o);
        return o.toByteArray();
    }

    private static byte[] docx() throws Exception
    {
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream o = new ByteArrayOutputStream())
        {
            d.createParagraph().createRun().setText("Bonjour");
            d.write(o);
            return o.toByteArray();
        }
    }

    private static byte[] xlsx() throws Exception
    {
        try (XSSFWorkbook w = new XSSFWorkbook(); ByteArrayOutputStream o = new ByteArrayOutputStream())
        {
            w.createSheet("a").createRow(0).createCell(0).setCellValue("x");
            w.write(o);
            return o.toByteArray();
        }
    }

    private static byte[] xls() throws Exception
    {
        try (HSSFWorkbook w = new HSSFWorkbook(); ByteArrayOutputStream o = new ByteArrayOutputStream())
        {
            w.createSheet("a").createRow(0).createCell(0).setCellValue("x");
            w.write(o);
            return o.toByteArray();
        }
    }

    private static byte[] zip(int entrees, int octetsParEntree) throws Exception
    {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o))
        {
            for (int i = 0; i < entrees; i++)
            {
                z.putNextEntry(new ZipEntry("fichier" + i + ".bin"));
                z.write(new byte[octetsParEntree]);
                z.closeEntry();
            }
        }
        return o.toByteArray();
    }

    private static byte[] texte(String s)
    {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ── cas nominal ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void unFichierCoherentEstAccepteSansAvertissement() throws Exception
    {
        assertThat(service.verifier("rapport.pdf", pdf(), null).avertissements()).isEmpty();
        assertThat(service.verifier("photo.png", image("png", 10), null).avertissements()).isEmpty();
        assertThat(service.verifier("photo.JPG", image("jpg", 10), null).avertissements()).isEmpty();
        assertThat(service.verifier("lettre.docx", docx(), null).avertissements()).isEmpty();
        assertThat(service.verifier("budget.xlsx", xlsx(), null).avertissements()).isEmpty();
        assertThat(service.verifier("budget.xls", xls(), null).avertissements()).isEmpty();
        assertThat(service.verifier("notes.txt", texte("bonjour"), null).avertissements()).isEmpty();
        verify(audit, never()).log(any(), eq(AuditAction.FICHIER_REFUSE), anyString(), anyBoolean());
    }

    @Test
    void csvEtTxtSeValentCarLeContenuEstDuTexte() throws Exception
    {
        assertThat(service.verifier("donnees.csv", texte("a;b\n1;2\n"), null).avertissements()).isEmpty();
        assertThat(service.verifier("donnees.txt", texte("a;b\n1;2\n"), null).avertissements()).isEmpty();
    }

    // ── le fichier ment sur son type ────────────────────────────────────────────────────────────────────────

    @Test
    void unPngNommeJpgEstRefuse() throws Exception
    {
        assertThatThrownBy(() -> service.verifier("photo.jpg", image("png", 10), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("incohérent").hasMessageContaining("image JPEG").hasMessageContaining("image PNG");
    }

    @Test
    void unWordNommePdfEtUnPdfNommeWordSontRefuses() throws Exception
    {
        assertThatThrownBy(() -> service.verifier("contrat.pdf", docx(), null)).isInstanceOf(BusinessException.class).hasMessageContaining("incohérent");
        assertThatThrownBy(() -> service.verifier("contrat.docx", pdf(), null)).isInstanceOf(BusinessException.class).hasMessageContaining("incohérent");
        assertThatThrownBy(() -> service.verifier("budget.docx", xlsx(), null)).isInstanceOf(BusinessException.class).hasMessageContaining("incohérent");
    }

    @Test
    void unZipDeguiseEnImageOuSansExtensionEstRefuseCommeArchive() throws Exception
    {
        byte[] piege = zip(2, 100);

        assertThatThrownBy(() -> service.verifier("photo.jpg", piege, null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("archive");
        assertThatThrownBy(() -> service.verifier("sans_extension", piege, null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("archive");
        assertThatThrownBy(() -> service.verifier("lot.zip", piege, null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("archive");
    }

    @Test
    void unExecutableOuUnePageHtmlSontRefusesQuelQueSoitLeNom() throws Exception
    {
        byte[] exe = new byte[2048];
        exe[0] = 'M'; exe[1] = 'Z';
        assertThatThrownBy(() -> service.verifier("facture.pdf", exe, null)).isInstanceOf(BusinessException.class).hasMessageContaining("Format non supporté");
        assertThatThrownBy(() -> service.verifier("page.txt", texte("<html><body><script>1</script></body></html>"), null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("Format non supporté");
    }

    // ── écarts tolérés et fichiers sans extension : acceptés AVEC avertissement ─────────────────────────────

    @Test
    void docEtDocxEchangesSontAcceptesAvecAvertissement() throws Exception
    {
        ControleTypeFichierService.Resultat r = service.verifier("lettre.doc", docx(), null);

        assertThat(r.mimeReel()).contains("wordprocessingml");
        assertThat(r.avertissements()).singleElement().asString()
            .contains(".doc").contains("Word (.docx)").contains("assumez");

        assertThat(service.verifier("budget.xlsx", xls(), null).avertissements()).singleElement().asString().contains("Excel 97-2003");
        assertThat(service.verifier("budget.xls", xlsx(), null).avertissements()).singleElement().asString().contains("Excel (.xlsx)");
    }

    @Test
    void unFichierSansExtensionEstAccepteSiSonTypeReelEstAutorise_etLeTypeDetecteEstIndique() throws Exception
    {
        ControleTypeFichierService.Resultat r = service.verifier("scan", image("png", 10), null);

        assertThat(r.mimeReel()).isEqualTo("image/png");
        assertThat(r.avertissements()).singleElement().asString().contains("sans extension").contains("image PNG");

        assertThat(service.verifier("document.dat", pdf(), null).avertissements())
            .singleElement().asString().contains("non reconnue").contains("PDF");
    }

    // ── lisibilité et pièges ────────────────────────────────────────────────────────────────────────────────

    @Test
    void desFichiersCorrompusSontRefuses() throws Exception
    {
        assertThatThrownBy(() -> service.verifier("a.pdf", texte("%PDF-1.4\nce n'est pas un vrai PDF"), null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("illisible");
        byte[] pngCasse = new byte[200];
        byte[] signature = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
        System.arraycopy(signature, 0, pngCasse, 0, signature.length);
        assertThatThrownBy(() -> service.verifier("a.png", pngCasse, null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("illisible");
        assertThatThrownBy(() -> service.verifier("vide.pdf", new byte[0], null)).isInstanceOf(BusinessException.class).hasMessageContaining("vide");
    }

    @Test
    void uneImageAuxDimensionsDemesureesEstRefusee() throws Exception
    {
        ControleTypeFichierService.Limites limites = new ControleTypeFichierService.Limites();
        limites.maxPixelsImage = 100;
        ControleTypeFichierService strict = new ControleTypeFichierService(audit, limites);

        assertThatThrownBy(() -> strict.verifier("grande.png", image("png", 20), null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("démesurées");
        assertThat(strict.verifier("petite.png", image("png", 9), null).avertissements()).isEmpty();
    }

    @Test
    void uneBombeDeDecompressionEstRefuseeSurSonIndexSansRienDecompresser() throws Exception
    {
        ControleTypeFichierService.Limites limites = new ControleTypeFichierService.Limites();
        limites.tailleEntreeMinRatio = 1024 * 1024;     // le taux s'évalue dès 1 Mo
        ControleTypeFichierService strict = new ControleTypeFichierService(audit, limites);
        byte[] bombe = zip(1, 3 * 1024 * 1024);          // 3 Mo de zéros : quelques Ko compressés

        assertThatThrownBy(() -> strict.verifier("photo.jpg", bombe, null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("compression");
    }

    @Test
    void unConteneurAvecTropDEntreesEstRefuse() throws Exception
    {
        ControleTypeFichierService.Limites limites = new ControleTypeFichierService.Limites();
        limites.maxEntreesZip = 3;
        ControleTypeFichierService strict = new ControleTypeFichierService(audit, limites);

        assertThatThrownBy(() -> strict.verifier("lettre.docx", docx(), null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("entrées");
        assertThatThrownBy(() -> strict.verifier("lot.bin", zip(10, 10), null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("entrées");
    }

    // ── trace d'audit ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void chaqueRefusEstTraceDansLeJournalAvecLesTypesEnCause() throws Exception
    {
        assertThatThrownBy(() -> service.verifier("photo.jpg", image("png", 10), null)).isInstanceOf(BusinessException.class);

        verify(audit).log(isNull(), eq(AuditAction.FICHIER_REFUSE),
            org.mockito.ArgumentMatchers.argThat((String d) -> d.contains("photo.jpg") && d.contains(".jpg") && d.contains("image PNG")),
            eq(false));
    }

    private static <T> T any()
    {
        return org.mockito.ArgumentMatchers.any();
    }
}
