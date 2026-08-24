package made.archive.service.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import lombok.extern.slf4j.Slf4j;
import made.archive.exception.BusinessException;

/**
 * Construit à la volée le PDF d'une attestation d'archivage — jamais stocké
 * (voir Attestation), reconstruit à chaque consultation publique. Page A4,
 * polices standard PDFBox (WinAnsiEncoding — couvre les accents français,
 * pas besoin d'embarquer une police externe).
 *
 * Logo : chargé depuis le classpath "attestation/logo.png" s'il existe
 * (voir README de ce dossier) — sinon repli silencieux sur un en-tête
 * texte seul "MadeArchive". Emplacement volontairement dans
 * src/main/resources pour être embarqué dans le jar (donc disponible aussi
 * en Docker), contrairement à un chemin fichier relatif au répertoire de
 * travail qui ne survivrait pas à l'empaquetage.
 */
@Slf4j
@Service
public class AttestationPdfService
{
    private static final String LOGO_CLASSPATH = "attestation/logo.png";
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");

    private static final float MARGE       = 50f;
    private static final float LARGEUR_A4  = PDRectangle.A4.getWidth();
    private static final float LARGEUR_UTILE = LARGEUR_A4 - 2 * MARGE;

    private static final PDFont POLICE_TITRE   = PDType1Font.HELVETICA_BOLD;
    private static final PDFont POLICE_TEXTE   = PDType1Font.HELVETICA;
    private static final PDFont POLICE_ITALIQUE = PDType1Font.HELVETICA_OBLIQUE;

    public byte[] genererPdf(AttestationPdfData data)
    {
        try (PDDocument document = new PDDocument())
        {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            BufferedImage logo = chargerLogo();

            try (PDPageContentStream cs = new PDPageContentStream(document, page))
            {
                float y = 792f;

                y = dessinerEntete(document, cs, logo, y);
                y = ligneHorizontale(cs, y - 10) - 25;

                y = texteCentre(cs, POLICE_TITRE, 16, "ATTESTATION D'ARCHIVAGE", y);
                y -= 25;

                y = paragraphe(cs,
                    "Ce document atteste que le fichier ci-dessous a été archivé de manière "
                    + "intègre sur la plateforme MadeArchive, avec préservation de son "
                    + "intégrité et de ses métadonnées au moment de l'archivage.",
                    POLICE_TEXTE, 10, y, LARGEUR_UTILE);
                y -= 20;

                y = sectionTitre(cs, "Informations du document", y);
                y = champ(cs, "Titre", data.titreDocument(), y);
                y = champ(cs, "Type de document", data.typeDocumentNom(), y);
                y = champ(cs, "Date d'archivage", data.dateArchivage().format(DATE_FORMAT), y);
                y -= 15;

                y = sectionTitre(cs, "Métadonnées à l'archivage", y);
                if (data.metadonnees().isEmpty())
                {
                    y = texte(cs, POLICE_ITALIQUE, 10, "Aucune métadonnée enregistrée.", MARGE, y);
                    y -= 18;
                }
                else
                {
                    for (AttestationPdfData.MetaEntry meta : data.metadonnees())
                    {
                        y = champ(cs, meta.label(), meta.valeur(), y);
                    }
                }
                y -= 15;

                y = sectionTitre(cs, "Archivé par", y);
                y = champ(cs, "Nom", data.uploadeurNomComplet(), y);
                y = champ(cs, "Email", data.uploadeurEmail(), y);
                y = champ(cs, "Téléphone", data.uploadeurTelephone(), y);

                dessinerQrEtLien(document, cs, data.lienPublic());
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
        catch (IOException e)
        {
            log.error("[Attestation] Erreur génération PDF : {}", e.getMessage(), e);
            throw new BusinessException("Impossible de générer l'attestation : " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Mise en page
    // ─────────────────────────────────────────────────────────────────────

    private float dessinerEntete(PDDocument document, PDPageContentStream cs,
                                  BufferedImage logo, float y) throws IOException
    {
        float texteX = MARGE;

        if (logo != null)
        {
            PDImageXObject logoImg = LosslessFactory.createFromImage(document, logo);
            float logoHauteur = 45f;
            float logoLargeur = logoHauteur * logo.getWidth() / (float) logo.getHeight();
            cs.drawImage(logoImg, MARGE, y - logoHauteur + 8, logoLargeur, logoHauteur);
            texteX = MARGE + logoLargeur + 12;
        }

        texte(cs, POLICE_TITRE, 20, "MadeArchive", texteX, y - 15);
        texte(cs, POLICE_ITALIQUE, 9, "Plateforme d'archivage institutionnel", texteX, y - 30);

        return y - 45;
    }

    private void dessinerQrEtLien(PDDocument document, PDPageContentStream cs, String lien)
        throws IOException
    {
        float qrTaille = 110f;
        float qrX = (LARGEUR_A4 - qrTaille) / 2f;
        float qrY = 130f;

        try
        {
            BufferedImage qrImage = genererQrCode(lien, 300);
            PDImageXObject qrPdImage = LosslessFactory.createFromImage(document, qrImage);
            cs.drawImage(qrPdImage, qrX, qrY, qrTaille, qrTaille);
        }
        catch (WriterException e)
        {
            log.warn("[Attestation] QR code non généré : {}", e.getMessage());
        }

        texteCentre(cs, POLICE_TEXTE, 9,
            "Scannez ce code pour consulter et télécharger le document original", qrY - 12);
        texteCentre(cs, POLICE_ITALIQUE, 8, lien, qrY - 26);

        texteCentre(cs, POLICE_ITALIQUE, 7,
            "Ce document atteste l'existence et l'intégrité du fichier archivé — "
            + "il ne remplace pas l'original.", 55f);
    }

    private BufferedImage genererQrCode(String contenu, int taille) throws WriterException
    {
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(contenu, BarcodeFormat.QR_CODE, taille, taille);
        return MatrixToImageWriter.toBufferedImage(matrix);
    }

    private BufferedImage chargerLogo()
    {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(LOGO_CLASSPATH))
        {
            if (is == null)
            {
                return null;
            }
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("[Attestation] Logo introuvable/illisible, repli sur texte seul : {}",
                e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Primitives de dessin de texte
    // ─────────────────────────────────────────────────────────────────────

    private float sectionTitre(PDPageContentStream cs, String titre, float y) throws IOException
    {
        texte(cs, POLICE_TITRE, 12, titre, MARGE, y);
        return y - 18;
    }

    private float champ(PDPageContentStream cs, String label, String valeur, float y)
        throws IOException
    {
        String affichee = (valeur == null || valeur.isBlank()) ? "—" : valeur;
        String ligne = label + " : " + affichee;
        return paragraphe(cs, ligne, POLICE_TEXTE, 10, y, LARGEUR_UTILE) - 4;
    }

    private float texte(PDPageContentStream cs, PDFont police, float taille,
                         String contenu, float x, float y) throws IOException
    {
        cs.beginText();
        cs.setFont(police, taille);
        cs.newLineAtOffset(x, y);
        cs.showText(contenu);
        cs.endText();
        return y;
    }

    private float texteCentre(PDPageContentStream cs, PDFont police, float taille,
                               String contenu, float y) throws IOException
    {
        float largeur = police.getStringWidth(contenu) / 1000 * taille;
        float x = (LARGEUR_A4 - largeur) / 2f;
        texte(cs, police, taille, contenu, x, y);
        return y;
    }

    private float ligneHorizontale(PDPageContentStream cs, float y) throws IOException
    {
        cs.setLineWidth(0.75f);
        cs.moveTo(MARGE, y);
        cs.lineTo(LARGEUR_A4 - MARGE, y);
        cs.stroke();
        return y;
    }

    /**
     * Retour à la ligne manuel (police non proportionnelle-safe via
     * getStringWidth) — les valeurs de métadonnées ou titres longs ne sont
     * jamais garantis de tenir sur une seule ligne.
     */
    private float paragraphe(PDPageContentStream cs, String contenu, PDFont police,
                              float taille, float y, float largeurMax) throws IOException
    {
        List<String> lignes = decouper(contenu, police, taille, largeurMax);
        for (String ligne : lignes)
        {
            texte(cs, police, taille, ligne, MARGE, y);
            y -= taille + 4;
        }
        return y;
    }

    private List<String> decouper(String contenu, PDFont police, float taille, float largeurMax)
        throws IOException
    {
        List<String> lignes = new ArrayList<>();
        StringBuilder courante = new StringBuilder();

        for (String mot : contenu.split(" "))
        {
            String essai = courante.isEmpty() ? mot : courante + " " + mot;
            if (police.getStringWidth(essai) / 1000 * taille > largeurMax && !courante.isEmpty())
            {
                lignes.add(courante.toString());
                courante = new StringBuilder(mot);
            }
            else
            {
                courante = new StringBuilder(essai);
            }
        }
        if (!courante.isEmpty())
        {
            lignes.add(courante.toString());
        }
        return lignes;
    }
}
