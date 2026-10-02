package made.archive.service.document;

import made.archive.config.PdfAProperties;
import made.archive.exception.PdfAConversionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Les tests de conversion nécessitent Ghostscript (présent dans l'image
 * Docker de l'application) : ils sont ignorés, pas en échec, sur un poste qui
 * ne l'a pas. Chemin surchargeable par la variable GHOSTSCRIPT_PATH.
 */
class PdfAConversionServiceTest
{
    private static final String TEXTE =
        "Facture fournisseur numero 2026-042 emise le 15 mars 2026 par la societe Exemple "
        + "pour un montant total de 1250 euros toutes taxes comprises payable sous trente jours";

    private static PdfAConversionService service;
    private static boolean ghostscriptDisponible;

    @BeforeAll
    static void initialiser() throws Exception
    {
        PdfAProperties props = new PdfAProperties();
        String chemin = System.getenv("GHOSTSCRIPT_PATH");
        if (chemin != null && !chemin.isBlank())
        {
            props.setGhostscriptChemin(chemin);
        }
        service = new PdfAConversionService(props);
        service.initialiser();
        ghostscriptDisponible = ghostscriptDisponible(props.getGhostscriptChemin());
    }

    // ── Mesure de fidélité du texte (sans Ghostscript) ──────────────────────

    @Test
    void fidelite_texteIdentique_vaut1()
    {
        assertThat(PdfAConversionService.mesurerFideliteTexte(TEXTE, TEXTE)).isEqualTo(1.0);
    }

    @Test
    void fidelite_ignoreCasseLigaturesEtPonctuation()
    {
        String source   = TEXTE + " ﬁnancier, ﬁnal.";
        String resultat = TEXTE.toUpperCase() + " financier final";
        assertThat(PdfAConversionService.mesurerFideliteTexte(source, resultat)).isEqualTo(1.0);
    }

    @Test
    void fidelite_texteDegrade_estMesure()
    {
        // Moitié des mots remplacés par des glyphes illisibles
        String[] mots = TEXTE.split(" ");
        StringBuilder degrade = new StringBuilder();
        for (int i = 0; i < mots.length; i++)
        {
            degrade.append(i % 2 == 0 ? mots[i] : "□□□").append(' ');
        }
        Double fidelite = PdfAConversionService.mesurerFideliteTexte(TEXTE, degrade.toString());
        assertThat(fidelite).isBetween(0.45, 0.6);
    }

    @Test
    void fidelite_sourceSansCoucheTexte_nonMesuree()
    {
        assertThat(PdfAConversionService.mesurerFideliteTexte("", "n'importe quoi")).isNull();
        assertThat(PdfAConversionService.mesurerFideliteTexte("trop court", "trop court")).isNull();
    }

    // ── Conversion réelle (Ghostscript + veraPDF) ───────────────────────────

    @Test
    void pdfOrdinaireAvecPoliceNonIntegree_estConvertiEtValide() throws Exception
    {
        assumeTrue(ghostscriptDisponible, "Ghostscript absent");

        // Helvetica : police "standard 14", jamais intégrée par PDFBox — c'est
        // exactement le cas d'un PDF bureautique ordinaire non conforme.
        byte[] source = pdf(PDType1Font.HELVETICA, 2);

        PdfAConversionService.ResultatPdfA resultat = service.convertirEtVerifier(source, "facture.pdf");

        assertThat(resultat.rapport().conversionEffectuee()).isTrue();
        assertThat(resultat.rapport().profil()).isEqualTo("PDF/A-3b");
        assertThat(resultat.rapport().nombrePages()).isEqualTo(2);
        assertThat(resultat.rapport().nombrePolices()).isPositive();
        assertThat(resultat.rapport().fideliteTexte()).isGreaterThanOrEqualTo(0.90);
        assertThat(resultat.pdfBytes()).isNotEqualTo(source);
    }

    @Test
    void pdfDejaConforme_estConserveSansModification() throws Exception
    {
        assumeTrue(ghostscriptDisponible, "Ghostscript absent");

        byte[] dejaPdfA = service.convertirEtVerifier(pdf(PDType1Font.HELVETICA, 1), "a.pdf").pdfBytes();

        PdfAConversionService.ResultatPdfA resultat = service.convertirEtVerifier(dejaPdfA, "a.pdf");

        assertThat(resultat.rapport().conversionEffectuee()).isFalse();
        assertThat(resultat.pdfBytes()).isEqualTo(dejaPdfA);
    }

    @Test
    void pdfChiffre_estRefuse() throws Exception
    {
        byte[] chiffre;
        try (PDDocument document = PDDocument.load(pdf(PDType1Font.HELVETICA, 1)))
        {
            StandardProtectionPolicy politique =
                new StandardProtectionPolicy("proprietaire", "utilisateur", new AccessPermission());
            politique.setEncryptionKeyLength(128);
            document.protect(politique);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            chiffre = out.toByteArray();
        }

        assertThatThrownBy(() -> service.convertirEtVerifier(chiffre, "protege.pdf"))
            .isInstanceOf(PdfAConversionException.class)
            .hasMessageContaining("mot de passe");
    }

    // ── Outils ──────────────────────────────────────────────────────────────

    private static byte[] pdf(PDFont police, int pages) throws Exception
    {
        try (PDDocument document = new PDDocument())
        {
            for (int p = 0; p < pages; p++)
            {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream contenu = new PDPageContentStream(document, page))
                {
                    contenu.beginText();
                    contenu.setFont(police, 11);
                    contenu.setLeading(14);
                    contenu.newLineAtOffset(50, 700);
                    for (String ligne : TEXTE.split("(?<=\\G.{60})"))
                    {
                        contenu.showText(ligne);
                        contenu.newLine();
                    }
                    contenu.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static boolean ghostscriptDisponible(String chemin)
    {
        try
        {
            Process p = new ProcessBuilder(chemin, "--version").redirectErrorStream(true).start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
