package made.archive.service.fichier;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import made.archive.entite.AuditAction;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.service.audit.AuditLogService;

/**
 * Contrôle d'un fichier déposé, AVANT toute conversion — le même pour les trois modes de versement (unitaire, en masse,
 * lien web), puisqu'ils convergent tous vers DocumentOcrService.processOcrPreview.
 *
 * Trois questions, dans l'ordre :
 *  1. Quel est le type RÉEL ? Lu dans le contenu (Tika), jamais dans le nom. Les archives (zip, rar, 7z…) sont refusées
 *     d'emblée : l'application ne décompresse JAMAIS un dépôt.
 *  2. Ce type correspond-il à ce que le nom ANNONCE ? Sinon le fichier est refusé. Exceptions : un fichier sans
 *     extension (ou d'extension inconnue) est accepté si son type réel est autorisé, un .doc/.docx (et .xls/.xlsx,
 *     .ppt/.pptx) échangés sont acceptés — dans les deux cas AVEC avertissement, l'éditeur validant en connaissance de cause.
 *  3. Le fichier est-il réellement lisible, et sans piège ? PDF ouvrable, image décodable et de taille raisonnable,
 *     document Office valide, conteneur ZIP (docx/xlsx/pptx/odf) sans bombe de décompression : nombre d'entrées, taille
 *     annoncée et taux de compression bornés, vérifiés sur l'index du ZIP SANS rien décompresser.
 *
 * Chaque refus est tracé dans le journal d'audit (signal de sécurité : un utilisateur qui multiplie les fichiers
 * déguisés se voit).
 */
@Slf4j
@Service
public class ControleTypeFichierService
{
    /** Seuils de sécurité — fixés par défaut pour des documents bureautiques courants, modifiables pour les tests. */
    public static class Limites
    {
        public int  maxEntreesZip          = 20_000;
        public long maxOctetsDecompresses  = 2L * 1024 * 1024 * 1024;   // 2 Go, taille annoncée cumulée
        public long tailleEntreeMinRatio   = 20L * 1024 * 1024;         // le taux ne s'évalue qu'au-delà de 20 Mo
        public double ratioMax             = 100.0;                     // décompressé / compressé, par entrée
        public long maxPixelsImage         = 120_000_000L;              // ~ A0 à 300 dpi
    }

    public record Resultat(String mimeReel, String libelleReel, List<String> avertissements) {}

    private static final byte[] MAGIC_ZIP = { 'P', 'K', 3, 4 };

    private final Tika tika = new Tika();
    private final AuditLogService auditLogService;
    private final Limites limites;

    @Autowired
    public ControleTypeFichierService(AuditLogService auditLogService)
    {
        this(auditLogService, new Limites());
    }

    ControleTypeFichierService(AuditLogService auditLogService, Limites limites)
    {
        this.auditLogService = auditLogService;
        this.limites = limites;
    }

    /**
     * @param acteur l'éditeur qui dépose (pour la trace d'audit en cas de refus) — peut être null
     * @throws BusinessException fichier refusé, avec un message destiné à l'utilisateur
     */
    public Resultat verifier(String nomFichier, byte[] contenu, User acteur)
    {
        String nom = nomFichier != null ? nomFichier : "(sans nom)";
        String extension = TypesFichiers.extensionDuNom(nom);

        if (contenu == null || contenu.length == 0)
        {
            throw refuser(acteur, nom, extension, null, "« " + nom + " » est vide.");
        }

        // 0. Protège la détection elle-même : un ZIP s'inspecte sur son index avant que Tika n'en parcoure les entrées.
        boolean estZip = commencePar(contenu, MAGIC_ZIP);
        if (estZip)
        {
            String probleme = inspecterIndexZip(contenu);
            if (probleme != null)
            {
                throw refuser(acteur, nom, extension, "archive ZIP", "« " + nom + " » est refusé : " + probleme + ".");
            }
        }

        // 1. Type réel.
        String mime = detecter(contenu);
        String libelleReel = TypesFichiers.libelle(mime);

        if (TypesFichiers.ARCHIVES.containsKey(mime) || "application/zip".equals(mime))
        {
            throw refuser(acteur, nom, extension, libelleReel, "« " + nom + " » est une " + libelleReel
                + " : les archives ne sont pas acceptées, déposez les documents eux-mêmes.");
        }
        if (!TypesFichiers.estAutorise(mime))
        {
            throw refuser(acteur, nom, extension, libelleReel,
                "Format non supporté : « " + nom + " » est de type « " + libelleReel + " ».");
        }

        // 2. Cohérence avec le nom.
        List<String> avertissements = new ArrayList<>();
        if (extension == null || !TypesFichiers.extensionConnue(extension))
        {
            avertissements.add(extension == null
                ? "Fichier sans extension : format détecté « " + libelleReel + " »."
                : "Extension « ." + extension + " » non reconnue : format détecté « " + libelleReel + " ».");
        }
        else if (!TypesFichiers.correspond(extension, mime))
        {
            if (TypesFichiers.ecartTolere(extension, mime))
            {
                avertissements.add("Le fichier est nommé « ." + extension + " » mais son format réel est « " + libelleReel
                    + " ». Vérifiez l'aperçu avant de valider : vous assumez l'archivage de ce document tel quel.");
            }
            else
            {
                throw refuser(acteur, nom, extension, libelleReel, "Type de fichier incohérent : « " + nom
                    + " » est annoncé comme « " + TypesFichiers.libelleDeExtension(extension)
                    + " » mais son contenu est de type « " + libelleReel + " ». Le fichier est refusé.");
            }
        }

        // 3. Lisibilité et pièges.
        try
        {
            verifierStructure(mime, contenu);
        }
        catch (ProblemeStructure e)
        {
            throw refuser(acteur, nom, extension, libelleReel,
                "« " + nom + " » est illisible ou invalide (" + libelleReel + ") : " + e.getMessage() + ".");
        }

        return new Resultat(mime, libelleReel, List.copyOf(avertissements));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private String detecter(byte[] contenu)
    {
        try
        {
            return tika.detect(contenu);
        }
        catch (Exception e)
        {
            return "application/octet-stream";
        }
    }

    private BusinessException refuser(User acteur, String nom, String extension, String libelleReel, String message)
    {
        log.warn("[ControleType] Refusé : {} (annoncé : {}, réel : {}) — {}", nom, extension, libelleReel, message);
        try
        {
            String proprete = nom.replaceAll("[\\p{Cntrl}]", "?");
            if (proprete.length() > 120) proprete = proprete.substring(0, 120) + "…";
            auditLogService.log(acteur, AuditAction.FICHIER_REFUSE,
                "Fichier refusé « " + proprete + " » — extension annoncée : " + (extension != null ? "." + extension : "aucune")
                    + ", type réel : " + (libelleReel != null ? libelleReel : "n/a") + " — " + message, false);
        }
        catch (Exception e)
        {
            log.warn("[ControleType] Trace d'audit non écrite (best-effort) : {}", e.getMessage());
        }
        return new BusinessException(message);
    }

    private static boolean commencePar(byte[] contenu, byte[] signature)
    {
        if (contenu.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++)
        {
            if (contenu[i] != signature[i]) return false;
        }
        return true;
    }

    // ── Conteneurs ZIP : index seulement, rien n'est décompressé ─────────────────────────────────────────────

    /** @return la raison du refus, ou null si l'index du ZIP est raisonnable (ou illisible : traité plus loin). */
    private String inspecterIndexZip(byte[] contenu)
    {
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(contenu)).get())
        {
            int entrees = 0;
            long total = 0;
            for (Iterator<ZipArchiveEntry> it = zip.getEntries().asIterator(); it.hasNext(); )
            {
                ZipArchiveEntry e = it.next();
                if (++entrees > limites.maxEntreesZip)
                {
                    return "archive suspecte (plus de " + limites.maxEntreesZip + " entrées)";
                }
                long taille = Math.max(0, e.getSize());
                long comprime = Math.max(0, e.getCompressedSize());
                total += taille;
                if (total > limites.maxOctetsDecompresses)
                {
                    return "archive suspecte (contenu annoncé de plus de "
                        + (limites.maxOctetsDecompresses / (1024 * 1024)) + " Mo une fois décompressé)";
                }
                if (taille >= limites.tailleEntreeMinRatio && (comprime == 0 || (double) taille / comprime > limites.ratioMax))
                {
                    return "taux de compression anormal (bombe de décompression probable)";
                }
            }
            return null;
        }
        catch (Exception e)
        {
            // Index illisible : pas de verdict ici — un vrai ZIP corrompu sera refusé comme archive, un document
            // Office corrompu par le contrôle de structure.
            return null;
        }
    }

    // ── Structure par type ───────────────────────────────────────────────────────────────────────────────

    private static class ProblemeStructure extends Exception
    {
        ProblemeStructure(String message) { super(message); }
    }

    private void verifierStructure(String mime, byte[] contenu) throws ProblemeStructure
    {
        switch (mime)
        {
            case TypesFichiers.PDF -> verifierPdf(contenu);
            case "image/jpeg", "image/png", "image/tiff", "image/bmp", "image/gif" -> verifierImage(contenu);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                verifierConteneur(contenu, "word/document.xml");
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                verifierConteneur(contenu, "xl/workbook.xml");
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                verifierConteneur(contenu, "ppt/presentation.xml");
            case "application/vnd.oasis.opendocument.text",
                 "application/vnd.oasis.opendocument.spreadsheet",
                 "application/vnd.oasis.opendocument.presentation" -> verifierConteneur(contenu, "content.xml");
            case "application/msword" -> verifierOle2(contenu, Set.of("WordDocument"));
            case "application/vnd.ms-excel" -> verifierOle2(contenu, Set.of("Workbook", "Book"));
            case "application/vnd.ms-powerpoint" -> verifierOle2(contenu, Set.of("PowerPoint Document"));
            default -> { /* texte brut, CSV : rien de structurel à vérifier */ }
        }
    }

    private void verifierPdf(byte[] contenu) throws ProblemeStructure
    {
        try (PDDocument document = PDDocument.load(contenu, "", null, null, MemoryUsageSetting.setupMixed(32L * 1024 * 1024)))
        {
            if (document.getNumberOfPages() < 1)
            {
                throw new ProblemeStructure("aucune page");
            }
        }
        catch (InvalidPasswordException e)
        {
            throw new ProblemeStructure("protégé par mot de passe");
        }
        catch (ProblemeStructure e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ProblemeStructure("fichier PDF corrompu");
        }
    }

    private void verifierImage(byte[] contenu) throws ProblemeStructure
    {
        try (ImageInputStream flux = ImageIO.createImageInputStream(new ByteArrayInputStream(contenu)))
        {
            Iterator<ImageReader> lecteurs = ImageIO.getImageReaders(flux);
            if (!lecteurs.hasNext())
            {
                throw new ProblemeStructure("image non décodable");
            }
            ImageReader lecteur = lecteurs.next();
            try
            {
                lecteur.setInput(flux, true, true);
                // Dimensions lues dans l'en-tête, sans décoder l'image : une "bombe" est une image minuscule en octets
                // mais gigantesque en pixels.
                long pixels = (long) lecteur.getWidth(0) * lecteur.getHeight(0);
                if (pixels <= 0 || pixels > limites.maxPixelsImage)
                {
                    throw new ProblemeStructure("dimensions d'image démesurées (" + lecteur.getWidth(0) + " × "
                        + lecteur.getHeight(0) + " pixels)");
                }
            }
            finally
            {
                lecteur.dispose();
            }
        }
        catch (ProblemeStructure e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ProblemeStructure("image corrompue");
        }
    }

    /** Document Office/OpenDocument = ZIP : index lisible, bornes respectées, et pièce maîtresse présente. */
    private void verifierConteneur(byte[] contenu, String pieceMaitresse) throws ProblemeStructure
    {
        String probleme = inspecterIndexZip(contenu);
        if (probleme != null)
        {
            throw new ProblemeStructure(probleme);
        }
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(contenu)).get())
        {
            if (zip.getEntry(pieceMaitresse) == null)
            {
                throw new ProblemeStructure("document incomplet (« " + pieceMaitresse + " » absent)");
            }
        }
        catch (ProblemeStructure e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ProblemeStructure("document corrompu");
        }
    }

    private void verifierOle2(byte[] contenu, Set<String> fluxAttendus) throws ProblemeStructure
    {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(contenu)))
        {
            boolean present = fluxAttendus.stream().anyMatch(fs.getRoot()::hasEntry);
            if (!present)
            {
                throw new ProblemeStructure("document incomplet");
            }
        }
        catch (ProblemeStructure e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ProblemeStructure("document corrompu");
        }
    }
}
