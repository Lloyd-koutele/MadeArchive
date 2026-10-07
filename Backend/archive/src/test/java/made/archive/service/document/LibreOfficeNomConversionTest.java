package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Le nom envoyé à Gotenberg porte l'extension du type RÉEL : LibreOffice choisit son filtre d'import d'après elle. */
@Tag("unit")
class LibreOfficeNomConversionTest
{
    private static final String WORD = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Test
    void remplaceUneExtensionMensongere()
    {
        assertThat(LibreOfficeConversionService.nomAvecExtensionReelle("budget.xlsx", WORD)).isEqualTo("budget.docx");
        assertThat(LibreOfficeConversionService.nomAvecExtensionReelle("rapport.final.v2.doc", WORD)).isEqualTo("rapport.final.v2.docx");
    }

    @Test
    void ajouteLExtensionManquante()
    {
        assertThat(LibreOfficeConversionService.nomAvecExtensionReelle("scan", "image/png")).isEqualTo("scan.png");
        assertThat(LibreOfficeConversionService.nomAvecExtensionReelle(null, "image/png")).isEqualTo("document.png");
    }

    @Test
    void laisseInchangeUnTypeSansExtensionCanonique()
    {
        assertThat(LibreOfficeConversionService.nomAvecExtensionReelle("a.bin", "application/octet-stream")).isEqualTo("a.bin");
    }
}
