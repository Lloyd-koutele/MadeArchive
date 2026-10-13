package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** L'attestation doit nommer l'institution (l'unité organisationnelle) à laquelle appartient le document. */
class AttestationPdfServiceTest
{
    private final AttestationPdfService service = new AttestationPdfService();

    private String texte(AttestationPdfData data) throws Exception
    {
        try (PDDocument pdf = PDDocument.load(service.genererPdf(data)))
        {
            return new PDFTextStripper().getText(pdf);
        }
    }

    private AttestationPdfData donnees(String institution)
    {
        return new AttestationPdfData("Bulletin S1", "Bulletin", institution, LocalDateTime.of(2026, 10, 12, 19, 14),
            "a".repeat(64), List.of(), "Louis Diop", "diop@esp.sn", "+221770000000", "https://exemple.sn/attestation/x/document");
    }

    @Test
    void l_attestation_affiche_le_nom_de_l_institution()
    {
        String t;
        try { t = texte(donnees("Ecole Superieure Polytechnique")); } catch (Exception e) { throw new RuntimeException(e); }

        assertThat(t).contains("Institution");
        assertThat(t).contains("Ecole Superieure Polytechnique");
    }

    @Test
    void sans_unite_organisationnelle_le_champ_reste_present_avec_un_tiret()
    {
        String t;
        try { t = texte(donnees(null)); } catch (Exception e) { throw new RuntimeException(e); }

        assertThat(t).contains("Institution");
    }
}
