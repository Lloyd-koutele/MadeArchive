package made.archive.service.integrite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
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

import made.archive.dto.RapportVerificationDto;
import made.archive.dto.RapportVerificationDto.Etape;
import made.archive.exception.BusinessException;

/** Rapport PDF d'une vérification instantanée (voir {@link VerificationDocumentService}). */
@Service
public class RapportVerificationPdfService
{
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm:ss")
        .withZone(ZoneId.of("Africa/Dakar"));
    private static final PDFont GRAS = PDType1Font.HELVETICA_BOLD;
    private static final PDFont TEXTE = PDType1Font.HELVETICA;
    private static final PDFont MONO = PDType1Font.COURIER;
    private static final float MARGE = 50f;
    private static final float LARGEUR = PDRectangle.A4.getWidth() - 2 * MARGE;
    private static final float HAUT = PDRectangle.A4.getHeight() - 60f;
    private static final float BAS = 60f;

    /** Écrit les lignes une à une en ouvrant une nouvelle page quand la précédente est pleine. */
    private static final class Plume
    {
        final PDDocument doc;
        PDPageContentStream cs;
        float y;

        Plume(PDDocument doc) throws IOException
        {
            this.doc = doc;
            nouvellePage();
        }

        void nouvellePage() throws IOException
        {
            if (cs != null) cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = HAUT;
        }

        void ligne(PDFont police, float taille, String texte, float x, float[] couleur) throws IOException
        {
            if (y < BAS) nouvellePage();
            cs.beginText();
            cs.setFont(police, taille);
            cs.setNonStrokingColor(couleur[0], couleur[1], couleur[2]);
            cs.newLineAtOffset(x, y);
            cs.showText(propre(texte, police));
            cs.endText();
            y -= taille + 4;
        }

        void bloc(PDFont police, float taille, String texte, float x, float largeur, float[] couleur) throws IOException
        {
            for (String l : decouper(texte, police, taille, largeur)) ligne(police, taille, l, x, couleur);
        }

        void espace(float h) { y -= h; }
    }

    private static final float[] NOIR = {0, 0, 0};
    private static final float[] GRIS = {0.35f, 0.35f, 0.35f};
    private static final float[] VERT = {0.1f, 0.5f, 0.25f};
    private static final float[] ORANGE = {0.8f, 0.45f, 0f};
    private static final float[] ROUGE = {0.75f, 0.1f, 0.1f};

    public byte[] genererPdf(RapportVerificationDto r)
    {
        try (PDDocument doc = new PDDocument())
        {
            Plume p = new Plume(doc);
            p.ligne(GRAS, 20, "MadeArchive", MARGE, NOIR);
            p.ligne(TEXTE, 9, "Plateforme d'archivage institutionnel", MARGE, GRIS);
            p.espace(14);
            p.ligne(GRAS, 16, "RAPPORT DE VERIFICATION D'INTEGRITE", MARGE, NOIR);
            p.espace(10);

            float[] couleur = switch (r.getVerdict())
            {
                case RapportVerificationDto.CONFORME -> r.isDegrade() ? ORANGE : VERT;
                case RapportVerificationDto.PREUVES_ALTEREES, RapportVerificationDto.NON_VERIFIABLE -> ORANGE;
                default -> ROUGE;
            };
            p.ligne(GRAS, 13, "Verdict : " + libelleVerdict(r), MARGE, couleur);
            p.bloc(TEXTE, 10, r.getConclusion(), MARGE, LARGEUR, NOIR);
            p.espace(12);

            p.ligne(GRAS, 11, "Document", MARGE, NOIR);
            champ(p, "Titre", r.getTitre());
            champ(p, "Identifiant", String.valueOf(r.getDocumentId()));
            champ(p, "Version", r.getVersion() == null ? "-" : String.valueOf(r.getVersion()));
            champ(p, "Verification demandee le", DATE.format(r.getVerifieLe()));
            champ(p, "Verification demandee par", r.getVerifiePar());
            if (r.getHorodatageAttesteLe() != null)
            {
                champ(p, "Date attestee par l'autorite d'horodatage", DATE.format(r.getHorodatageAttesteLe()));
            }
            p.espace(8);
            p.ligne(GRAS, 11, "Empreintes SHA-256 (PDF/A)", MARGE, NOIR);
            p.ligne(TEXTE, 9, "Enregistree a l'archivage :", MARGE, GRIS);
            p.ligne(MONO, 8, nonNul(r.getEmpreinteEnregistree()), MARGE, NOIR);
            p.ligne(TEXTE, 9, "Recalculee sur le fichier stocke :", MARGE, GRIS);
            p.ligne(MONO, 8, nonNul(r.getEmpreinteRecalculee()), MARGE, NOIR);
            p.espace(10);

            p.ligne(GRAS, 11, "Controles effectues", MARGE, NOIR);
            for (Etape e : r.getEtapes())
            {
                float[] c = switch (e.getStatut())
                {
                    case "OK" -> VERT;
                    case "ALERTE" -> ORANGE;
                    case "ECHEC" -> ROUGE;
                    default -> GRIS;
                };
                p.ligne(GRAS, 10, "[" + e.getStatut() + "] " + e.getLibelle(), MARGE, c);
                p.bloc(TEXTE, 9, e.getDetail(), MARGE + 14, LARGEUR - 14, NOIR);
                p.espace(3);
            }
            p.espace(10);
            p.bloc(TEXTE, 8, "Rapport en lecture seule : cette verification ne modifie ni le document ni son statut. "
                + "Les preuves consultees (cle du HSM, jeton RFC 3161, manifeste verrouille, journal chaine) sont "
                + "independantes de la base de donnees.", MARGE, LARGEUR, GRIS);
            p.cs.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
        catch (IOException e)
        {
            throw new BusinessException("Impossible de générer le rapport de vérification : " + e.getMessage());
        }
    }

    private static void champ(Plume p, String label, String valeur) throws IOException
    {
        p.bloc(TEXTE, 10, label + " : " + nonNul(valeur), MARGE, LARGEUR, NOIR);
    }

    private static String libelleVerdict(RapportVerificationDto r)
    {
        return switch (r.getVerdict())
        {
            case RapportVerificationDto.CONFORME -> r.isDegrade() ? "CONFORME (verification degradee)" : "CONFORME";
            case RapportVerificationDto.PREUVES_ALTEREES -> "FICHIER SAIN, PREUVES EN BASE ALTEREES";
            case RapportVerificationDto.FICHIER_ALTERE -> "FICHIER ALTERE";
            default -> "NON VERIFIABLE";
        };
    }

    private static String nonNul(String s) { return s == null || s.isBlank() ? "-" : s; }

    /** Helvetica/Courier standard n'encodent que WinAnsi : tout caractère hors jeu est remplacé. */
    private static String propre(String texte, PDFont police)
    {
        StringBuilder sb = new StringBuilder();
        for (char c : texte.replace('\n', ' ').replace('\t', ' ').toCharArray())
        {
            try
            {
                police.encode(String.valueOf(c));
                sb.append(c);
            }
            catch (Exception e)
            {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private static List<String> decouper(String texte, PDFont police, float taille, float largeur)
    {
        List<String> lignes = new ArrayList<>();
        StringBuilder courante = new StringBuilder();
        for (String mot : propre(texte == null ? "" : texte, police).split(" "))
        {
            String essai = courante.length() == 0 ? mot : courante + " " + mot;
            try
            {
                if (police.getStringWidth(essai) / 1000 * taille > largeur && courante.length() > 0)
                {
                    lignes.add(courante.toString());
                    courante = new StringBuilder(mot);
                    continue;
                }
            }
            catch (IOException e)
            {
                // largeur indéterminée : on garde le mot sur la ligne
            }
            courante = new StringBuilder(essai);
        }
        if (courante.length() > 0) lignes.add(courante.toString());
        return lignes;
    }
}
