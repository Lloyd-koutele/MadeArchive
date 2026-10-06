package made.archive.service.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

/**
 * Dessine le PDF d'un procès-verbal d'élimination (liste des documents éliminés, un bloc par document, sur
 * autant de pages que nécessaire). Produit un PDF ordinaire avec les polices standard : il est ensuite
 * converti en PDF/A par PdfAConversionService (voir ProcesVerbalEliminationService). Chaque bloc reproduit le
 * TITRE et les informations qui permettent d'identifier le document éliminé (type, activité, version, dossier,
 * emplacement, métadonnées, déposant, dates, empreinte, motif) : sans cela, le procès-verbal dirait pourquoi et
 * comment on a supprimé, mais pas QUOI. Ces informations pouvant être sensibles, le procès-verbal est archivé en
 * accès restreint (voir ProcesVerbalEliminationService).
 */
@Service
public class ProcesVerbalPdfService
{
    /**
     * Un document éliminé. {@code version}, {@code dossier}, {@code emplacement} et {@code deposePar} peuvent être
     * null (rien à indiquer) ; {@code metadonnees} est une liste de « nom : valeur » (peut être vide).
     */
    public record Ligne(String identifiant, String titre, String type, String activite, String version, String dossier,
                        String emplacement, String deposePar, LocalDate archiveLe, String conservation,
                        List<String> metadonnees, String empreinteSha256, String motif, String eliminePar,
                        String elimineLe) {}

    public record Donnees(String uoNom, LocalDate dateElimination, LocalDateTime etabliLe, List<Ligne> lignes) {}

    private static final PDFont POLICE_TITRE = PDType1Font.HELVETICA_BOLD;
    private static final PDFont POLICE_TEXTE = PDType1Font.HELVETICA;
    private static final PDFont POLICE_CODE  = PDType1Font.COURIER;
    private static final float MARGE = 45f;
    private static final float INTERLIGNE = 12f;
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public byte[] generer(Donnees d)
    {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            float largeur = PDRectangle.A4.getWidth() - 2 * MARGE;
            List<Bloc> blocs = new ArrayList<>();

            blocs.add(new Bloc(POLICE_TITRE, 17, List.of("Procès-verbal d'élimination")));
            blocs.add(new Bloc(POLICE_TEXTE, 10, enveloppe(
                "Unité organisationnelle : " + d.uoNom(), POLICE_TEXTE, 10, largeur)));
            blocs.add(new Bloc(POLICE_TEXTE, 10, List.of(
                "Date d'élimination : " + d.dateElimination().format(JOUR),
                "Établi le : " + d.etabliLe().format(HEURE) + " — nombre de documents éliminés : " + d.lignes().size())));
            blocs.add(new Bloc(POLICE_TEXTE, 8.5f, enveloppe(
                "Les documents ci-dessous ont été éliminés de MadeArchive : leur fichier et leur entrée de recherche ont été "
                + "supprimés ; seules sont conservées les informations qui permettent de les identifier (titre, type, "
                + "métadonnées, dates, empreinte SHA-256, motif). Ce procès-verbal est à accès restreint.",
                POLICE_TEXTE, 8.5f, largeur)));

            int n = 0;
            for (Ligne l : d.lignes())
            {
                n++;
                List<String> lignes = new ArrayList<>();
                lignes.addAll(enveloppe(n + ". " + titreOuTiret(l.titre()), POLICE_TITRE, 9.5f, largeur));
                lignes.add("   Document " + l.identifiant());
                lignes.addAll(enveloppe("   Type : " + l.type() + (l.activite() != null ? " — Activité : " + l.activite() : ""),
                    POLICE_TEXTE, 9, largeur));
                String classement = joindre(
                    l.version() != null ? "Version : " + l.version() : null,
                    l.dossier() != null ? "Dossier : " + l.dossier() : null,
                    l.emplacement() != null ? "Emplacement physique : " + l.emplacement() : null);
                if (classement != null)
                {
                    lignes.addAll(enveloppe("   " + classement, POLICE_TEXTE, 9, largeur));
                }
                lignes.addAll(enveloppe("   Archivé le " + l.archiveLe().format(JOUR)
                    + (l.deposePar() != null ? " par " + l.deposePar() : "") + " — " + l.conservation(),
                    POLICE_TEXTE, 9, largeur));
                if (l.metadonnees() != null && !l.metadonnees().isEmpty())
                {
                    lignes.addAll(enveloppe("   Métadonnées : " + String.join(" ; ", l.metadonnees()), POLICE_TEXTE, 9, largeur));
                }
                lignes.add("   SHA-256 : " + l.empreinteSha256());
                lignes.addAll(enveloppe("   Motif : " + l.motif() + " — éliminé le " + l.elimineLe() + " par " + l.eliminePar(),
                    POLICE_TEXTE, 9, largeur));
                blocs.add(new Bloc(POLICE_TEXTE, 9, lignes).avecSha());
            }

            PDPage page = nouvellePage(pdf);
            PDPageContentStream cs = new PDPageContentStream(pdf, page);
            float y = PDRectangle.A4.getHeight() - MARGE;
            int numeroPage = 1;

            for (Bloc b : blocs)
            {
                float hauteur = b.lignes.size() * (b.taille + 3) + 8;
                if (y - hauteur < MARGE + 20)
                {
                    pied(cs, numeroPage);
                    cs.close();
                    page = nouvellePage(pdf);
                    cs = new PDPageContentStream(pdf, page);
                    y = PDRectangle.A4.getHeight() - MARGE;
                    numeroPage++;
                }
                for (String ligne : b.lignes)
                {
                    PDFont police = b.avecSha && ligne.startsWith("   SHA-256") ? POLICE_CODE
                        : b.avecSha && ligne.matches("^\\d+\\. .*") ? POLICE_TITRE : b.police;
                    float taille = police == POLICE_CODE ? 7.5f : b.taille;
                    y -= taille + 3;
                    cs.beginText();
                    cs.setFont(police, taille);
                    cs.newLineAtOffset(MARGE, y);
                    cs.showText(sur(ligne));
                    cs.endText();
                }
                y -= 8;
            }
            pied(cs, numeroPage);
            cs.close();

            pdf.save(out);
            return out.toByteArray();
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Génération du PDF du procès-verbal impossible", e);
        }
    }

    private static String titreOuTiret(String titre)
    {
        return titre == null || titre.isBlank() ? "(sans titre)" : "« " + titre.trim() + " »";
    }

    private static String joindre(String... morceaux)
    {
        List<String> gardes = new ArrayList<>();
        for (String m : morceaux)
        {
            if (m != null) gardes.add(m);
        }
        return gardes.isEmpty() ? null : String.join(" — ", gardes);
    }

    private static final class Bloc
    {
        final PDFont police; final float taille; final List<String> lignes; boolean avecSha;
        Bloc(PDFont police, float taille, List<String> lignes) { this.police = police; this.taille = taille; this.lignes = lignes; }
        Bloc avecSha() { this.avecSha = true; return this; }
    }

    private PDPage nouvellePage(PDDocument pdf)
    {
        PDPage page = new PDPage(PDRectangle.A4);
        pdf.addPage(page);
        return page;
    }

    private void pied(PDPageContentStream cs, int numero) throws IOException
    {
        cs.beginText();
        cs.setFont(POLICE_TEXTE, 8);
        cs.newLineAtOffset(MARGE, MARGE - 5);
        cs.showText("MadeArchive — procès-verbal d'élimination — page " + numero);
        cs.endText();
    }

    /** Coupe un texte aux espaces pour qu'il tienne dans la largeur donnée. */
    private List<String> enveloppe(String texte, PDFont police, float taille, float largeur) throws IOException
    {
        List<String> lignes = new ArrayList<>();
        StringBuilder courante = new StringBuilder();
        for (String mot : sur(texte).split(" "))
        {
            String essai = courante.length() == 0 ? mot : courante + " " + mot;
            if (police.getStringWidth(essai) / 1000 * taille > largeur && courante.length() > 0)
            {
                lignes.add(courante.toString());
                courante = new StringBuilder("   " + mot);
            }
            else
            {
                courante = new StringBuilder(essai);
            }
        }
        lignes.add(courante.toString());
        return lignes;
    }

    /** Les polices standard n'encodent que Windows-1252 : tout autre caractère (ou saut de ligne) devient "?" / espace. */
    static String sur(String texte)
    {
        if (texte == null) return "";
        CharsetEncoder winAnsi = Charset.forName("windows-1252").newEncoder(); // un encodeur n'est pas thread-safe
        StringBuilder sb = new StringBuilder(texte.length());
        for (char c : texte.toCharArray())
        {
            if (c == '\n' || c == '\r' || c == '\t') sb.append(' ');
            else sb.append(c >= 32 && winAnsi.canEncode(c) ? c : '?');
        }
        return sb.toString();
    }
}
