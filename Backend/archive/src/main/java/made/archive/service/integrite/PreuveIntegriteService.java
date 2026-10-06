package made.archive.service.integrite;

import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.Document;
import made.archive.security.HsmKeyStoreService;
import made.archive.security.PkiService;
import made.archive.service.document.HashService;
import made.archive.service.integrite.HorodatageVerificationService.Etat;
import made.archive.service.integrite.ManifestePreuveService.Manifeste;

/**
 * Arbitre d'intégrité d'un document : confronte le fichier réellement stocké à des preuves que la base de
 * données SEULE ne permet pas de réécrire.
 *
 * Sources de confiance, toutes indépendantes de PostgreSQL :
 *   1. les clés publiques du KeyStore HSM (jamais users.pki_public_key) — signature de l'enregistrement canonique
 *      du document (empreintes, UO, version, date, clé de stockage, signature du hash) et signature du hash PDF/A ;
 *   2. les jetons d'horodatage RFC 3161 dont le certificat TSA a été reçu en direct (TsaAncreService) ;
 *   3. le manifeste verrouillé dans le bucket de preuves MinIO (ManifestePreuveService).
 *
 * Ce qui permet enfin de DISTINGUER une altération du fichier d'une altération de la preuve en base : si une de
 * ces sources atteste le fichier tel qu'il est, mais que la base dit autre chose, c'est la base qui a été
 * modifiée — le document n'est pas déclaré corrompu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreuveIntegriteService
{
    /** Alias de la clé du système (procès-verbaux, rattrapage des anciens documents, racines d'ancrage). */
    public static final String ALIAS_SYSTEME = "systeme-archive";

    private static final String PREFIXE_CANONIQUE = "MADEARCHIVE-ENREGISTREMENT-V1";

    public enum Verdict
    {
        /** Fichier, empreinte et preuves concordent. */
        OK,
        /** Le fichier est attesté par une preuve indépendante, mais l'enregistrement en base ne l'est plus. */
        PREUVE_ALTEREE,
        /** Le fichier n'est attesté par aucune preuve fiable (ou la contredit). */
        FICHIER_ALTERE
    }

    public enum EtatEnregistrement { NON_SCELLE, CLE_INDISPONIBLE, CONFORME, ALTERE }

    /**
     * @param degrade true si aucune racine de confiance n'a pu être consultée (alias absent du KeyStore, ancien
     *                document sans signature...) : le verdict retombe alors sur la seule comparaison d'empreinte.
     */
    public record Constat(Verdict verdict, String raison, boolean degrade) {}

    private final HsmKeyStoreService hsm;
    private final PkiService pkiService;
    private final HashService hashService;
    private final HorodatageVerificationService jetons;
    private final ManifestePreuveService manifestes;

    @PostConstruct
    void garantirCleSysteme()
    {
        try
        {
            if (!hsm.hasKey(ALIAS_SYSTEME))
            {
                hsm.storePrivateKey(ALIAS_SYSTEME, pkiService.generateNativeKeyPair());
                log.info("[Preuves] Clé système '{}' créée dans le HSM fichier", ALIAS_SYSTEME);
            }
        }
        catch (Exception e)
        {
            log.warn("[Preuves] Clé système non garantie au démarrage : {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────
    // Enregistrement canonique + scellement
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Date de création à la seconde — la base garde les microsecondes, Java les nanosecondes : seule la seconde
     *  est identique des deux côtés après un aller-retour. */
    static String dateCanonique(Document d)
    {
        return d.getCreateAt().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** Valeurs signées, dans un ordre FIXE (jamais une sérialisation d'objet dont l'ordre peut changer). */
    String canonique(Document d)
    {
        return canonique(d, d.getPdfaSha256());
    }

    /**
     * Même enregistrement, avec une autre empreinte PDF/A : sert à savoir si CE fichier est bien celui que la
     * signature d'origine couvrait, même quand l'empreinte stockée en base a été remplacée. La signature du hash
     * (pkiSignature) n'en fait volontairement pas partie — elle se vérifie séparément, et pouvoir tester le
     * fichier malgré une pkiSignature réécrite est justement ce qui distingue une preuve altérée d'un fichier altéré.
     */
    String canonique(Document d, String hashPdfa)
    {
        return String.join("|",
            PREFIXE_CANONIQUE,
            String.valueOf(d.getId()),
            hashPdfa,
            d.getOriginalSha256(),
            d.getUniteOrganisationnelle() != null ? String.valueOf(d.getUniteOrganisationnelle().getId()) : "-",
            String.valueOf(d.getVersion()),
            dateCanonique(d),
            d.getStorageKey());
    }

    String empreinteCanonique(Document d)
    {
        return empreinteCanonique(d, d.getPdfaSha256());
    }

    String empreinteCanonique(Document d, String hashPdfa)
    {
        return hashService.calculateFromBytes(canonique(d, hashPdfa).getBytes(StandardCharsets.UTF_8));
    }

    /** Signe l'enregistrement avec la clé de cet alias. Ne persiste pas : à l'appelant de sauvegarder. */
    public void sceller(Document d, String alias)
    {
        d.setSignatureEnregistrement(hsm.sign(alias, empreinteCanonique(d)));
        d.setSignatureEnregistrementAlias(alias);
    }

    /**
     * Pour un document produit par le système (procès-verbal) ou un ancien document : signe d'abord le hash PDF/A
     * s'il ne l'était pas, puis l'enregistrement — tout avec la clé système.
     */
    public void scellerParLeSysteme(Document d)
    {
        if (d.getPkiSignature() == null || d.getPkiSignature().isBlank())
        {
            d.setPkiSignature(hsm.sign(ALIAS_SYSTEME, d.getPdfaSha256()));
        }
        sceller(d, ALIAS_SYSTEME);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────
    // Vérifications unitaires
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Alias susceptibles d'avoir signé le hash PDF/A : clé de l'éditeur, convention d'alias, clé système. */
    private List<String> aliasSignataires(Document d)
    {
        Set<String> alias = new LinkedHashSet<>();
        if (d.getUploadedBy() != null)
        {
            if (d.getUploadedBy().getPkiKeyAlias() != null)
            {
                alias.add(d.getUploadedBy().getPkiKeyAlias());
            }
            alias.add("editor-" + d.getUploadedBy().getId());
        }
        alias.add(ALIAS_SYSTEME);
        return new ArrayList<>(alias);
    }

    private Optional<Boolean> signatureHashValide(List<String> aliasCandidats, String hashHex, String signature)
    {
        if (signature == null || signature.isBlank())
        {
            return Optional.empty();
        }
        boolean cleConsultee = false;
        for (String alias : aliasCandidats)
        {
            Optional<Boolean> r = hsm.verifier(alias, hashHex, signature);
            if (r.isPresent())
            {
                cleConsultee = true;
                if (r.get())
                {
                    return Optional.of(true);
                }
            }
        }
        return cleConsultee ? Optional.of(false) : Optional.empty();
    }

    /** pkiSignature (celle de la base) valide pour ce hash ? Vide = aucune signature ou aucune clé de confiance. */
    Optional<Boolean> signatureHashValide(Document d, String hashHex)
    {
        return signatureHashValide(aliasSignataires(d), hashHex, d.getPkiSignature());
    }

    /**
     * L'empreinte PDF/A stockée en base est-elle couverte par une signature de confiance ? Sert de garde-fou avant
     * d'obtenir un jeton d'horodatage. Vrai aussi quand RIEN n'est consultable (ancien document sans signature, clé
     * absente du KeyStore) : on ne bloque l'horodatage que sur une signature positivement INVALIDE.
     */
    public boolean empreinteAuthentique(Document d)
    {
        Optional<Boolean> sig = signatureHashValide(d, d.getPdfaSha256());
        EtatEnregistrement enreg = etatEnregistrement(d);
        if (sig.orElse(false) || enreg == EtatEnregistrement.CONFORME)
        {
            return true;
        }
        return sig.isEmpty() && enreg != EtatEnregistrement.ALTERE;
    }

    /** Clé publique (PEM) de confiance qui vérifie la signature de ce document — lue dans le KeyStore, jamais en base. */
    public Optional<String> clePubliqueDeConfiance(Document d)
    {
        for (String alias : aliasSignataires(d))
        {
            Optional<String> pem = hsm.clePubliquePem(alias);
            if (pem.isPresent() && d.getPkiSignature() != null
                && hsm.verifier(alias, d.getPdfaSha256(), d.getPkiSignature()).orElse(false))
            {
                return pem;
            }
        }
        return aliasSignataires(d).stream().map(hsm::clePubliquePem).flatMap(Optional::stream).findFirst();
    }

    EtatEnregistrement etatEnregistrement(Document d)
    {
        if (d.getSignatureEnregistrement() == null || d.getSignatureEnregistrement().isBlank())
        {
            return EtatEnregistrement.NON_SCELLE;
        }
        if (d.getSignatureEnregistrementAlias() == null)
        {
            return EtatEnregistrement.ALTERE;
        }
        return hsm.verifier(d.getSignatureEnregistrementAlias(), empreinteCanonique(d), d.getSignatureEnregistrement())
            .map(ok -> ok ? EtatEnregistrement.CONFORME : EtatEnregistrement.ALTERE)
            .orElse(EtatEnregistrement.CLE_INDISPONIBLE);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────
    // Arbitrage avec le fichier (contrôle d'intégrité)
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Règle de décision, par ordre de priorité :
     *   1. une preuve INDÉPENDANTE de la base et de ses clés (jeton d'horodatage d'un TSA reconnu, manifeste
     *      verrouillé) ATTESTE ce fichier : il est intact — OK si la base concorde, PREUVE_ALTEREE sinon ;
     *   2. l'une d'elles le CONTREDIT (elle atteste une autre empreinte) : fichier altéré, même si la base a été
     *      réécrite et re-signée ;
     *   3. sinon, les signatures des clés du HSM tranchent (hash signé, enregistrement signé) ;
     *   4. sinon (aucune clé de confiance consultable) : comparaison d'empreinte seule, signalée dégradée.
     *
     * @param hashFichier SHA-256 recalculé sur le PDF/A déchiffré lu dans MinIO
     */
    public Constat evaluer(Document d, String hashFichier)
    {
        boolean hashBaseOk = hashFichier.equals(d.getPdfaSha256());

        Optional<Boolean> sigFichier = signatureHashValide(d, hashFichier);
        Optional<Boolean> sigBase = hashBaseOk ? sigFichier : signatureHashValide(d, d.getPdfaSha256());
        EtatEnregistrement enreg = etatEnregistrement(d);
        boolean enregSurFichier = d.getSignatureEnregistrement() != null
            && d.getSignatureEnregistrementAlias() != null
            && hsm.verifier(d.getSignatureEnregistrementAlias(), empreinteCanonique(d, hashFichier),
                d.getSignatureEnregistrement()).orElse(false);

        // L'empreinte en base est AUTHENTIQUE si une clé de confiance la couvre (signature du hash ou de l'enregistrement).
        boolean baseAuthentique = sigBase.orElse(false) || enreg == EtatEnregistrement.CONFORME;
        // La signature du hash (pkiSignature) ne fait pas partie de l'enregistrement scellé : son altération seule
        // doit être relevée séparément.
        boolean baseConcorde = hashBaseOk && baseAuthentique && enreg != EtatEnregistrement.ALTERE
            && !(sigBase.isPresent() && !sigBase.get());

        Etat jeton = jetons.verifier(d.getHorodatageToken(), hashFichier);
        Optional<Manifeste> manifeste = manifestes.lire(d.getId());
        boolean manifesteValide = manifeste
            .flatMap(m -> signatureHashValide(aliasSignataires(d), m.pdfaSha256(), m.pkiSignature()))
            .orElse(false);
        boolean manifesteAtteste = manifesteValide && hashFichier.equals(manifeste.get().pdfaSha256());
        boolean manifesteContredit = manifesteValide && !hashFichier.equals(manifeste.get().pdfaSha256());

        boolean concours = jeton == Etat.ATTESTE_ANCRE || manifesteAtteste;
        boolean contradiction = jeton == Etat.EMPREINTE_DIFFERENTE || manifesteContredit;

        List<String> faits = new ArrayList<>();
        if (!hashBaseOk)
        {
            faits.add("l'empreinte en base diffère de celle du fichier");
        }
        if (sigBase.isPresent() && !sigBase.get())
        {
            faits.add("la signature du hash en base ne correspond pas");
        }
        if (enreg == EtatEnregistrement.ALTERE)
        {
            faits.add("la signature de l'enregistrement est invalide");
        }
        if (jeton == Etat.EMPREINTE_DIFFERENTE)
        {
            faits.add("le jeton d'horodatage atteste une autre empreinte");
        }
        if (manifesteContredit)
        {
            faits.add("le manifeste verrouillé atteste une autre empreinte");
        }

        // 1. Preuve indépendante qui atteste ce fichier.
        if (concours)
        {
            if (baseConcorde && !contradiction)
            {
                return new Constat(Verdict.OK, null, false);
            }
            String preuve = jeton == Etat.ATTESTE_ANCRE ? "jeton d'horodatage" : "manifeste verrouillé";
            return new Constat(Verdict.PREUVE_ALTEREE, "Fichier conforme (" + preuve + ") mais preuves en base altérées : "
                + (faits.isEmpty() ? "l'enregistrement en base n'est plus conforme à ses preuves" : String.join(", ", faits)), false);
        }

        // 2. Preuve indépendante qui contredit ce fichier.
        if (contradiction)
        {
            return new Constat(Verdict.FICHIER_ALTERE, "Fichier différent de l'empreinte attestée par une preuve "
                + "indépendante de la base : " + String.join(", ", faits), false);
        }

        // 3. Les signatures du HSM tranchent.
        if (baseConcorde)
        {
            return new Constat(Verdict.OK, null, false);
        }
        if (sigFichier.orElse(false) || enregSurFichier)
        {
            return new Constat(Verdict.PREUVE_ALTEREE, "Fichier conforme (" + (sigFichier.orElse(false)
                ? "signature du hash" : "signature de l'enregistrement") + ", clé du HSM) mais preuves en base altérées : "
                + (faits.isEmpty() ? "l'enregistrement en base n'est plus conforme à ses preuves" : String.join(", ", faits)), false);
        }

        // 4. Aucune racine de confiance consultable.
        boolean aucuneRacine = sigFichier.isEmpty() && enreg != EtatEnregistrement.ALTERE
            && enreg != EtatEnregistrement.CONFORME;
        if (aucuneRacine)
        {
            // Rien ne permet de juger autrement que par l'empreinte stockée (ancien document non signé, KeyStore
            // sans la clé) : comportement historique, signalé comme dégradé.
            return hashBaseOk
                ? new Constat(Verdict.OK, null, true)
                : new Constat(Verdict.FICHIER_ALTERE, "Empreinte SHA-256 différente de celle archivée à l'upload", true);
        }

        String detail = hashBaseOk
            ? "le fichier et l'empreinte en base concordent mais aucune preuve de confiance ne les atteste"
            : "empreinte différente de l'empreinte archivée, qu'aucune preuve de confiance ne dément";
        return new Constat(Verdict.FICHIER_ALTERE, "Fichier non conforme aux preuves d'origine : " + detail, false);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────
    // Vérification publique, sans le fichier
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    public record VerificationPreuves(boolean authentique, boolean verifiable, List<String> constats) {}

    /**
     * Cohérence des preuves d'un document, SANS relire le fichier (appelée par un endpoint public : pas de
     * déchiffrement ni de téléchargement à la demande). Ne dit rien de l'intégrité actuelle du fichier — c'est le
     * rôle du contrôle d'intégrité, dont le dernier résultat est rapporté à côté.
     */
    public VerificationPreuves verifierPreuves(Document d)
    {
        List<String> constats = new ArrayList<>();
        boolean altere = false;

        Optional<Boolean> sig = signatureHashValide(d, d.getPdfaSha256());
        EtatEnregistrement enreg = etatEnregistrement(d);
        boolean authentique = sig.orElse(false) || enreg == EtatEnregistrement.CONFORME;

        if (sig.isPresent())
        {
            constats.add(sig.get() ? "Signature du hash PDF/A valide (clé du HSM)" : "Signature du hash PDF/A INVALIDE");
            altere |= !sig.get();
        }
        switch (enreg)
        {
            case CONFORME -> constats.add("Enregistrement du document conforme à sa signature");
            case ALTERE -> { constats.add("Enregistrement du document NON conforme à sa signature"); altere = true; }
            case NON_SCELLE -> constats.add("Enregistrement non encore scellé (ancien document)");
            case CLE_INDISPONIBLE -> constats.add("Clé de vérification de l'enregistrement indisponible");
        }

        Etat jeton = jetons.verifier(d.getHorodatageToken(), d.getPdfaSha256());
        switch (jeton)
        {
            case ATTESTE_ANCRE -> constats.add("Horodatage RFC 3161 valide, certificat de l'autorité reconnu");
            case ATTESTE_NON_ANCRE -> constats.add("Horodatage RFC 3161 cohérent, autorité pas encore reconnue (non probant)");
            case EMPREINTE_DIFFERENTE -> { constats.add("Horodatage RFC 3161 attestant une AUTRE empreinte"); altere = true; }
            case INVALIDE -> constats.add("Horodatage RFC 3161 illisible ou non fiable (non pris en compte)");
            case ABSENT -> constats.add("Pas encore d'horodatage RFC 3161");
        }

        Optional<Manifeste> manifeste = manifestes.lire(d.getId());
        if (manifeste.isPresent())
        {
            boolean concorde = d.getPdfaSha256().equals(manifeste.get().pdfaSha256());
            constats.add(concorde ? "Manifeste verrouillé concordant" : "Manifeste verrouillé en désaccord avec la base");
            altere |= !concorde;
        }

        boolean verifiable = authentique || altere;
        return new VerificationPreuves(authentique && !altere, verifiable, constats);
    }
}
