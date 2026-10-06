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

    private ProcesVerbalPdfService.Ligne ligne(String titre, String type, String motif)
    {
        return new ProcesVerbalPdfService.Ligne(UUID.randomUUID().toString(), titre, type, "03 Finances › 03.2 Factures",
            "2", "Dossier Fournisseurs 2020", "Armoire B / Étagère 3", "Awa Ndiaye (awa@test.local)",
            LocalDate.of(2020, 1, 15), "conservation 5 an(s) (échéance 15/01/2025), sort final DETRUIRE",
            List.of("Fournisseur : ACME", "Montant : 1500"),
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
    void lePvIdentifieChaqueDocumentParSonTitreEtSesInformations_etNeplantePasSurDesCaracteresHorsWinAnsi() throws Exception
    {
        ProcesVerbalPdfService.Ligne l = ligne("Facture ACME mars 2020", "Facture", "fin de vie du document → ✓ émoji 😀");
        byte[] pdf = service.generer(new ProcesVerbalPdfService.Donnees(
            "Département Informatique", LocalDate.of(2026, 10, 3), LocalDateTime.of(2026, 10, 3, 2, 30), List.of(l)));

        String t = texte(pdf);
        assertThat(t).contains("Procès-verbal d'élimination", "Département Informatique", l.identifiant(),
            "Facture ACME mars 2020", "Facture", "03 Finances › 03.2 Factures", "Version : 2",
            "Dossier : Dossier Fournisseurs 2020", "Emplacement physique : Armoire B / Étagère 3",
            "par Awa Ndiaye (awa@test.local)", "Fournisseur : ACME", "Montant : 1500",
            "a".repeat(64), "fin de vie du document", "nombre de documents éliminés : 1");
        // caractères non encodables : remplacés, pas d'exception
        assertThat(t).doesNotContain("✓").doesNotContain("😀");
        assertThat(t).doesNotContain("Aucun titre de document").contains("accès restreint");
    }

    @Test
    void champsFacultatifsAbsents_aucuneLigneVideNiMotNull() throws Exception
    {
        ProcesVerbalPdfService.Ligne l = new ProcesVerbalPdfService.Ligne(UUID.randomUUID().toString(), null, "Facture",
            null, null, null, null, null, LocalDate.of(2020, 1, 15), "conservation illimitée", List.of(),
            "b".repeat(64), "suppression légale", "le système (suppression automatique)", "03/10/2026 02:00");

        String t = texte(service.generer(new ProcesVerbalPdfService.Donnees(
            "UO", LocalDate.of(2026, 10, 3), LocalDateTime.of(2026, 10, 3, 2, 30), List.of(l))));

        assertThat(t).contains("(sans titre)", "Type : Facture").doesNotContain("null")
            .doesNotContain("Métadonnées :").doesNotContain("Dossier :").doesNotContain("Version :");
    }

    @Test
    void beaucoupDeDocuments_toutesLesLignesSontPresentes_surPlusieursPages() throws Exception
    {
        List<ProcesVerbalPdfService.Ligne> lignes = new ArrayList<>();
        for (int i = 0; i < 80; i++) lignes.add(ligne("Titre " + i, "Type " + i, "erreur d'archivage (commentaire " + i + ")"));

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
        assertThat(t).contains("page 1").contains("80. « Titre 79 »");
    }

    @Test
    void leTexteEstNettoye()
    {
        assertThat(ProcesVerbalPdfService.sur("a\nb\tc\u0001d")).isEqualTo("a b c?d");
        assertThat(ProcesVerbalPdfService.sur(null)).isEmpty();
    }
}
