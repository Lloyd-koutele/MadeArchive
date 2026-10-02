package made.archive.service.document;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.PdfAProperties;
import made.archive.exception.PdfAConversionException;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.verapdf.ReleaseDetails;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Production d'un PDF/A RÉELLEMENT conforme, puis vérification avant archivage.
 *
 * L'ancienne version se contentait d'ajouter au PDF des métadonnées XMP
 * DÉCLARANT "PDF/A-3b" (et un OutputIntent sRGB qui n'était en fait jamais
 * ajouté : le profil cherché n'existe pas dans PDFBox 2.0.x) — sans intégrer
 * les polices ni corriger quoi que ce soit. Un PDF importé tel quel portait
 * donc l'étiquette PDF/A sans l'être : une fausse déclaration de conformité,
 * rédhibitoire pour un système dont le cœur est la preuve.
 *
 * Chaîne actuelle :
 *   1. Le PDF reçu (sortie Gotenberg, déjà demandée en PDF/A pour les formats
 *      bureautiques — voir LibreOfficeConversionService — ou PDF importé tel
 *      quel) est validé par veraPDF. S'il est déjà conforme, il est conservé
 *      SANS AUCUNE retouche (aucun ré-encodage, signature électronique intacte).
 *   2. Sinon il est converti par Ghostscript (intégration ou substitution des
 *      polices, conversion des couleurs en sRGB, OutputIntent, XMP).
 *   3. Le résultat est vérifié, et refusé au moindre échec :
 *        - conformité veraPDF au profil visé ;
 *        - polices : toutes intégrées, aucune endommagée ;
 *        - lisibilité : chaque page s'ouvre et se rend, même nombre de pages
 *          que la source, texte de la source retrouvé (seuil configurable).
 *
 * Aucun PDF n'est jamais étiqueté PDF/A sans avoir passé ces contrôles.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfAConversionService
{
    private final PdfAProperties props;

    // Profil sRGB fourni par le JDK lui-même — rien à embarquer ni à chercher
    // sur le disque, identique quel que soit l'environnement (poste, conteneur).
    private Path profilIccSrgb;

    // Ne considère "mot" qu'une suite d'au moins 2 lettres/chiffres : la
    // ponctuation et les caractères isolés varient légitimement d'un moteur
    // d'extraction de texte à l'autre et fausseraient la mesure de fidélité.
    private static final int LONGUEUR_MIN_MOT = 2;

    // En dessous de ce nombre de mots, la source est considérée sans couche
    // texte exploitable (scan pur) — la mesure de fidélité n'a pas de sens.
    private static final int MOTS_MIN_POUR_FIDELITE = 20;

    // Résolution du rendu de contrôle : suffisante pour détecter une page qui
    // ne se dessine pas, assez basse pour rester rapide sur un long document.
    private static final float DPI_RENDU_CONTROLE = 30f;

    private static final int NB_ERREURS_VERAPDF_AFFICHEES = 5;

    /**
     * Rapport de vérification, conservé avec le résultat pour traçabilité.
     *
     * @param conversionEffectuee false si le PDF reçu était déjà conforme et
     *                            a été conservé octet pour octet
     * @param fideliteTexte       part des mots de la source retrouvés, null
     *                            si non mesurée (pas de conversion, ou scan)
     * @param signatureInvalidee  true si la source portait une signature
     *                            électronique que la conversion a rendue
     *                            invalide (le contenu signé a été réécrit)
     */
    public record RapportPdfA(
        String profil,
        boolean conversionEffectuee,
        int nombrePages,
        int nombrePolices,
        Double fideliteTexte,
        boolean signatureInvalidee,
        String validateur) {}

    public record ResultatPdfA(byte[] pdfBytes, RapportPdfA rapport) {}

    @PostConstruct
    void initialiser() throws IOException
    {
        if (props.getPartie() != 2 && props.getPartie() != 3)
        {
            throw new IllegalStateException(
                "pdfa.partie doit valoir 2 ou 3 (PDF/A-2b ou PDF/A-3b), trouvé : " + props.getPartie());
        }

        VeraGreenfieldFoundryProvider.initialise();

        profilIccSrgb = Files.createTempFile("madearchive-srgb-", ".icc");
        Files.write(profilIccSrgb, ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
        profilIccSrgb.toFile().deleteOnExit();
    }

    /**
     * Garantit un PDF/A conforme au profil configuré, ou lève une exception
     * dont le message explique à l'utilisateur pourquoi le document est refusé.
     */
    public ResultatPdfA convertirEtVerifier(byte[] pdfSource, String nomFichier)
            throws PdfAConversionException
    {
        PDFAFlavour profil = profilVise();

        Lecture source = lire(pdfSource, nomFichier, "source");

        List<String> erreursSource = validerVeraPdf(pdfSource, profil);
        if (erreursSource.isEmpty())
        {
            // Déjà conforme : on ne touche à rien, mais les contrôles de polices
            // et de lisibilité s'appliquent quand même (veraPDF ne rend pas les pages).
            int nbPolices = verifierPolices(pdfSource, nomFichier);
            verifierRendu(pdfSource, nomFichier);

            log.info("[PDF/A] {} déjà conforme {} — conservé sans modification",
                nomFichier, props.getLibelleProfil());
            return new ResultatPdfA(pdfSource, new RapportPdfA(
                props.getLibelleProfil(), false, source.nombrePages(), nbPolices,
                null, false, versionVeraPdf()));
        }

        log.info("[PDF/A] {} non conforme {} ({} règle(s) en échec) — conversion Ghostscript",
            nomFichier, props.getLibelleProfil(), erreursSource.size());

        if (source.signe())
        {
            log.warn("[PDF/A] {} porte une signature électronique : la conversion va l'invalider",
                nomFichier);
        }

        byte[] pdfA = convertirAvecGhostscript(pdfSource, nomFichier);

        // ── Vérifications du résultat — toute anomalie est bloquante ─────────
        List<String> erreurs = validerVeraPdf(pdfA, profil);
        if (!erreurs.isEmpty())
        {
            throw new PdfAConversionException(
                "Le document " + nomFichier + " n'a pas pu être rendu conforme "
                + props.getLibelleProfil() + " : " + resumer(erreurs));
        }

        int nbPolices = verifierPolices(pdfA, nomFichier);
        Lecture resultat = lire(pdfA, nomFichier, "PDF/A produit");
        verifierRendu(pdfA, nomFichier);

        if (resultat.nombrePages() != source.nombrePages())
        {
            throw new PdfAConversionException(
                "La conversion PDF/A de " + nomFichier + " a modifié le nombre de pages ("
                + source.nombrePages() + " → " + resultat.nombrePages() + ")");
        }

        Double fidelite = mesurerFideliteTexte(source.texte(), resultat.texte());
        if (fidelite != null && fidelite < props.getSeuilFideliteTexte())
        {
            throw new PdfAConversionException(String.format(
                "La conversion PDF/A de %s a dégradé le texte : seuls %.0f %% des mots "
                + "du document d'origine restent lisibles (minimum exigé : %.0f %%)",
                nomFichier, fidelite * 100, props.getSeuilFideliteTexte() * 100));
        }

        log.info("[PDF/A] ✅ {} converti en {} — {} page(s), {} police(s) intégrée(s), fidélité texte {}",
            nomFichier, props.getLibelleProfil(), resultat.nombrePages(), nbPolices,
            fidelite != null ? String.format("%.1f %%", fidelite * 100) : "non mesurée (pas de couche texte)");

        return new ResultatPdfA(pdfA, new RapportPdfA(
            props.getLibelleProfil(), true, resultat.nombrePages(), nbPolices,
            fidelite, source.signe(), versionVeraPdf()));
    }

    // ── Lecture ─────────────────────────────────────────────────────────────

    private record Lecture(int nombrePages, String texte, boolean signe) {}

    private Lecture lire(byte[] pdf, String nomFichier, String role) throws PdfAConversionException
    {
        try (PDDocument document = PDDocument.load(pdf))
        {
            if (document.isEncrypted())
            {
                throw new PdfAConversionException(
                    "Le PDF " + nomFichier + " est chiffré ou protégé : il ne peut pas être "
                    + "archivé tel quel. Retirez la protection puis réessayez.");
            }
            return new Lecture(
                document.getNumberOfPages(),
                new PDFTextStripper().getText(document),
                !document.getSignatureDictionaries().isEmpty());
        }
        catch (PdfAConversionException e)
        {
            throw e;
        }
        catch (InvalidPasswordException e)
        {
            throw new PdfAConversionException(
                "Le PDF " + nomFichier + " est protégé par un mot de passe : il ne peut pas être "
                + "archivé tel quel. Retirez la protection puis réessayez.", e);
        }
        catch (Exception e)
        {
            throw new PdfAConversionException(
                "Le " + role + " de " + nomFichier + " est illisible ou corrompu : " + e.getMessage(), e);
        }
    }

    // ── veraPDF ─────────────────────────────────────────────────────────────

    private PDFAFlavour profilVise()
    {
        return props.getPartie() == 2 ? PDFAFlavour.PDFA_2_B : PDFAFlavour.PDFA_3_B;
    }

    /** Liste des règles en échec ("6.2.11.4.1 : message"), vide si conforme. */
    private List<String> validerVeraPdf(byte[] pdf, PDFAFlavour profil) throws PdfAConversionException
    {
        try (PDFAParser parser = Foundries.defaultInstance().createParser(new ByteArrayInputStream(pdf), profil);
             PDFAValidator validator = Foundries.defaultInstance().createValidator(profil, false))
        {
            ValidationResult resultat = validator.validate(parser);
            if (resultat.isCompliant())
            {
                return List.of();
            }
            // Une même règle échoue souvent des centaines de fois (une par
            // glyphe, par objet...) : on ne garde qu'une ligne par règle.
            Set<String> regles = new LinkedHashSet<>();
            for (TestAssertion a : resultat.getTestAssertions())
            {
                if (a.getStatus() == TestAssertion.Status.FAILED)
                {
                    regles.add(a.getRuleId().getClause() + " : " + a.getMessage());
                }
            }
            return regles.isEmpty() ? List.of("non conforme (aucun détail fourni)") : new ArrayList<>(regles);
        }
        catch (Exception e)
        {
            throw new PdfAConversionException("Validation veraPDF impossible : " + e.getMessage(), e);
        }
    }

    private String versionVeraPdf()
    {
        return "veraPDF " + ReleaseDetails.byId("core-jakarta").getVersion();
    }

    private String resumer(List<String> erreurs)
    {
        String debut = erreurs.stream().limit(NB_ERREURS_VERAPDF_AFFICHEES).collect(Collectors.joining(" ; "));
        return erreurs.size() > NB_ERREURS_VERAPDF_AFFICHEES
            ? debut + " ; … (" + erreurs.size() + " règles en échec)"
            : debut;
    }

    // ── Polices ─────────────────────────────────────────────────────────────

    /**
     * Toutes les polices utilisées — pages ET formulaires imbriqués (en-têtes,
     * tampons, logos vectoriels sont souvent des XObjects) — doivent être
     * intégrées et lisibles. Retourne le nombre de polices distinctes.
     */
    private int verifierPolices(byte[] pdf, String nomFichier) throws PdfAConversionException
    {
        Map<String, PDFont> polices = new HashMap<>();
        try (PDDocument document = PDDocument.load(pdf))
        {
            Set<COSBase> dejaVus = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PDPage page : document.getPages())
            {
                collecterPolices(page.getResources(), polices, dejaVus);
            }

            List<String> nonIntegrees = new ArrayList<>();
            List<String> endommagees  = new ArrayList<>();
            for (Map.Entry<String, PDFont> entree : polices.entrySet())
            {
                if (!entree.getValue().isEmbedded())  nonIntegrees.add(entree.getKey());
                if (entree.getValue().isDamaged())    endommagees.add(entree.getKey());
            }

            if (!nonIntegrees.isEmpty())
            {
                throw new PdfAConversionException(
                    "Polices non intégrées dans " + nomFichier + " : " + trier(nonIntegrees));
            }
            if (!endommagees.isEmpty())
            {
                throw new PdfAConversionException(
                    "Polices endommagées (illisibles) dans " + nomFichier + " : " + trier(endommagees));
            }
            return polices.size();
        }
        catch (PdfAConversionException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new PdfAConversionException(
                "Vérification des polices impossible pour " + nomFichier + " : " + e.getMessage(), e);
        }
    }

    private void collecterPolices(PDResources ressources, Map<String, PDFont> polices, Set<COSBase> dejaVus)
            throws IOException
    {
        if (ressources == null)
        {
            return;
        }
        for (COSName nom : ressources.getFontNames())
        {
            PDFont police = ressources.getFont(nom);
            if (police != null)
            {
                polices.putIfAbsent(police.getName() != null ? police.getName() : nom.getName(), police);
            }
        }
        for (COSName nom : ressources.getXObjectNames())
        {
            PDXObject xobject = ressources.getXObject(nom);
            // dejaVus porte sur l'OBJET, pas sur son nom : un même formulaire
            // peut être référencé par chaque page (ou se référencer lui-même),
            // alors que deux formulaires distincts peuvent porter le même nom
            // ("Fm1") sur deux pages différentes.
            if (xobject instanceof PDFormXObject formulaire && dejaVus.add(formulaire.getCOSObject()))
            {
                collecterPolices(formulaire.getResources(), polices, dejaVus);
            }
        }
    }

    private String trier(List<String> noms)
    {
        return noms.stream().sorted(Comparator.naturalOrder()).collect(Collectors.joining(", "));
    }

    // ── Lisibilité ──────────────────────────────────────────────────────────

    /** Chaque page doit pouvoir être dessinée — détecte un contenu corrompu. */
    private void verifierRendu(byte[] pdf, String nomFichier) throws PdfAConversionException
    {
        int pageEnCours = 0;
        try (PDDocument document = PDDocument.load(pdf))
        {
            if (document.getNumberOfPages() == 0)
            {
                throw new PdfAConversionException("Le document " + nomFichier + " ne contient aucune page");
            }
            PDFRenderer rendu = new PDFRenderer(document);
            for (pageEnCours = 0; pageEnCours < document.getNumberOfPages(); pageEnCours++)
            {
                rendu.renderImageWithDPI(pageEnCours, DPI_RENDU_CONTROLE);
            }
        }
        catch (PdfAConversionException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new PdfAConversionException(
                "La page " + (pageEnCours + 1) + " de " + nomFichier + " ne peut pas être affichée : "
                + e.getMessage(), e);
        }
    }

    /**
     * Part des mots de la source retrouvés dans le résultat (comptage avec
     * multiplicité : un mot présent 3 fois doit l'être 3 fois). Null si la
     * source n'a pas de couche texte exploitable.
     */
    static Double mesurerFideliteTexte(String texteSource, String texteResultat)
    {
        List<String> motsSource = mots(texteSource);
        if (motsSource.size() < MOTS_MIN_POUR_FIDELITE)
        {
            return null;
        }
        Map<String, Integer> disponibles = new HashMap<>();
        for (String mot : mots(texteResultat))
        {
            disponibles.merge(mot, 1, Integer::sum);
        }
        int retrouves = 0;
        for (String mot : motsSource)
        {
            Integer reste = disponibles.get(mot);
            if (reste != null && reste > 0)
            {
                disponibles.put(mot, reste - 1);
                retrouves++;
            }
        }
        return (double) retrouves / motsSource.size();
    }

    private static List<String> mots(String texte)
    {
        if (texte == null || texte.isBlank())
        {
            return List.of();
        }
        // NFKC : ligatures ("ﬁ" → "fi") et formes de compatibilité, que
        // Ghostscript peut légitimement décomposer autrement que la source.
        String normalise = Normalizer.normalize(texte, Normalizer.Form.NFKC).toLowerCase();
        return Stream.of(normalise.split("[^\\p{L}\\p{N}]+"))
            .filter(m -> m.length() >= LONGUEUR_MIN_MOT)
            .toList();
    }

    // ── Ghostscript ─────────────────────────────────────────────────────────

    private byte[] convertirAvecGhostscript(byte[] pdfSource, String nomFichier) throws PdfAConversionException
    {
        Path dossier = null;
        try
        {
            dossier = Files.createTempDirectory("madearchive-pdfa-");
            Path entree     = dossier.resolve("source.pdf");
            Path sortie     = dossier.resolve("pdfa.pdf");
            Path definition = dossier.resolve("PDFA_def.ps");
            Files.write(entree, pdfSource);
            Files.writeString(definition, definitionPdfA(), StandardCharsets.US_ASCII);

            List<String> commande = List.of(
                props.getGhostscriptChemin(),
                "-dPDFA=" + props.getPartie(),
                "-dBATCH", "-dNOPAUSE", "-dNOOUTERSAVE", "-dQUIET",
                // 1 = en cas de construction impossible en PDF/A, Ghostscript
                // l'ignore et continue, plutôt que d'abandonner tout le document
                // — la validation veraPDF qui suit tranche de toute façon.
                "-dPDFACompatibilityPolicy=1",
                "-sColorConversionStrategy=RGB",
                "-sProcessColorModel=DeviceRGB",
                "-sDEVICE=pdfwrite",
                // Ghostscript tourne en mode SAFER : il doit être explicitement
                // autorisé à lire le profil ICC référencé par PDFA_def.ps.
                "--permit-file-read=" + profilIccSrgb.toAbsolutePath(),
                "-sOutputFile=" + sortie.toAbsolutePath(),
                definition.toAbsolutePath().toString(),
                entree.toAbsolutePath().toString());

            Process processus = new ProcessBuilder(commande)
                .redirectErrorStream(true)
                .redirectOutput(dossier.resolve("gs.log").toFile())
                .start();

            if (!processus.waitFor(props.getGhostscriptTimeoutSecondes(), TimeUnit.SECONDS))
            {
                processus.destroyForcibly();
                throw new PdfAConversionException(
                    "Conversion PDF/A de " + nomFichier + " interrompue : délai de "
                    + props.getGhostscriptTimeoutSecondes() + " s dépassé");
            }
            if (processus.exitValue() != 0 || !Files.exists(sortie) || Files.size(sortie) == 0)
            {
                String journal = Files.readString(dossier.resolve("gs.log"));
                throw new PdfAConversionException(
                    "Ghostscript n'a pas pu convertir " + nomFichier + " (code " + processus.exitValue()
                    + ") : " + journal.strip().lines().reduce((a, b) -> b).orElse("aucun détail"));
            }
            return Files.readAllBytes(sortie);
        }
        catch (PdfAConversionException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            throw new PdfAConversionException(
                "Ghostscript indisponible (" + props.getGhostscriptChemin() + ") : " + e.getMessage(), e);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new PdfAConversionException("Conversion PDF/A de " + nomFichier + " interrompue", e);
        }
        finally
        {
            supprimer(dossier);
        }
    }

    /**
     * Fichier PostScript de définition PDF/A attendu par Ghostscript : déclare
     * l'OutputIntent (profil de couleur de référence, obligatoire en PDF/A).
     * Adapté du PDFA_def.ps fourni avec Ghostscript.
     */
    private String definitionPdfA()
    {
        String cheminIcc = profilIccSrgb.toAbsolutePath().toString()
            .replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        return """
            %!
            /ICCProfile (CHEMIN_ICC) def
            [/_objdef {icc_PDFA} /type /stream /OBJ pdfmark
            [{icc_PDFA} <</N 3>> /PUT pdfmark
            [{icc_PDFA} ICCProfile (r) file /PUT pdfmark
            [/_objdef {OutputIntent_PDFA} /type /dict /OBJ pdfmark
            [{OutputIntent_PDFA} <<
              /Type /OutputIntent
              /S /GTS_PDFA1
              /DestOutputProfile {icc_PDFA}
              /OutputConditionIdentifier (sRGB IEC61966-2.1)
              /Info (sRGB IEC61966-2.1)
              /RegistryName (http://www.color.org)
            >> /PUT pdfmark
            [{Catalog} <</OutputIntents [ {OutputIntent_PDFA} ]>> /PUT pdfmark
            """.replace("CHEMIN_ICC", cheminIcc);
    }

    private void supprimer(Path dossier)
    {
        if (dossier == null)
        {
            return;
        }
        try (Stream<Path> fichiers = Files.walk(dossier))
        {
            fichiers.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        catch (IOException e)
        {
            log.warn("[PDF/A] Dossier temporaire non supprimé : {}", dossier);
        }
    }
}
