package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ProcesVerbalPdfServiceTest
{
    private final ProcesVerbalPdfService service = new ProcesVerbalPdfService();

    private ProcesVerbalPdfService.Ligne ligne(String type, String motif)
    {
        return new ProcesVerbalPdfService.Ligne(UUID.randomUUID().toString(), type, "03 Finances › 03.2 Factures",
            LocalDate.of(2020, 1, 15), "conservation 5 an(s) (échéance 15/01/2025), sort final DETRUIRE",
            "a".repeat(64), motif, "le système (suppression automatique)", "03/10/2026 02:00");
    }

    private String texte(byte[] pdf) throws Exception
    {
        try (PDDocument d = PDDocument.load(pdf))
        {
            return new PDFTextStripper().getText(d);
        }
    }

    @Test
    void lePvListeLesDocumentsSansAucunTitre_etNeplantePasSurDesCaracteresHorsWinAnsi() throws Exception
    {
        ProcesVerbalPdfService.Ligne l = ligne("Facture", "fin de vie du document → ✓ émoji 😀");
        byte[] pdf = service.generer(new ProcesVerbalPdfService.Donnees(
            "Département Informatique", LocalDate.of(2026, 10, 3), LocalDateTime.of(2026, 10, 3, 2, 30), List.of(l)));

        String t = texte(pdf);
        assertThat(t).contains("Procès-verbal d'élimination", "Département Informatique", l.identifiant(),
            "Facture", "a".repeat(64), "fin de vie du document", "nombre de documents éliminés : 1");
        // caractères non encodables : remplacés, pas d'exception
        assertThat(t).doesNotContain("✓").doesNotContain("😀");
        assertThat(t).contains("Aucun titre de document");
    }

    @Test
    void beaucoupDeDocuments_toutesLesLignesSontPresentes_surPlusieursPages() throws Exception
    {
        List<ProcesVerbalPdfService.Ligne> lignes = new ArrayList<>();
        for (int i = 0; i < 80; i++) lignes.add(ligne("Type " + i, "erreur d'archivage (commentaire " + i + ")"));

        byte[] pdf = service.generer(new ProcesVerbalPdfService.Donnees(
            "UO", LocalDate.of(2026, 10, 3), LocalDateTime.of(2026, 10, 3, 2, 30), lignes));

        try (PDDocument d = PDDocument.load(pdf))
        {
            assertThat(d.getNumberOfPages()).isGreaterThan(2);
        }
        String t = texte(pdf);
        for (ProcesVerbalPdfService.Ligne l : lignes)
        {
            assertThat(t).contains(l.identifiant());
        }
        assertThat(t).contains("page 1").contains("80. Document");
    }

    @Test
    void leTexteEstNettoye()
    {
        assertThat(ProcesVerbalPdfService.sur("a\nb\tc\u0001d")).isEqualTo("a b c?d");
        assertThat(ProcesVerbalPdfService.sur(null)).isEmpty();
    }
}
