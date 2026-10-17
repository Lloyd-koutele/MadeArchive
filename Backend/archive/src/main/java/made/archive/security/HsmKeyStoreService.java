package made.archive.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.HsmProperties;
import made.archive.exception.BusinessException;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.concurrent.locks.ReentrantReadWriteLock;


@Slf4j
@Service
@RequiredArgsConstructor
public class HsmKeyStoreService
{
    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final int TAILLE_CLE_RSA = 2048;
    private static final String NOM_FOURNISSEUR_PKCS11 = "SoftHSM";

    private final HsmProperties hsmProperties;

    /** Fournisseur PKCS#11 (SoftHSM) en mode PKCS11 ; null en mode PKCS12 (fichier). */
    private Provider fournisseurPkcs11;

    /** Magasin du jeton, ouvert une fois (le jeton est vivant : toute clé ajoutée y est immédiatement visible). */
    private KeyStore magasinPkcs11;

    /**
     * Verrou lecture/écriture (pas un verrou global) : signer (sign/hasKey)
     * est une opération de LECTURE — chaque appel charge sa propre copie du
     * KeyStore en mémoire, sans état partagé entre threads, donc plusieurs
     * éditeurs peuvent signer en même temps, en vrai parallèle. Seul le dépôt
     * d'une nouvelle clé (storePrivateKey, rare — création d'un éditeur) est
     * exclusif, pour protéger le cycle lecture-modification-écriture du
     * fichier contre une écriture concurrente.
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @PostConstruct
    void init()
    {
        if (Security.getProvider("BC") == null)
        {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }

        if (hsmProperties.estPkcs11())
        {
            initialiserPkcs11();
            return;
        }

        File file = keystoreFile();
        if (!file.exists())
        {
            lock.writeLock().lock();
            try
            {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs())
                {
                    throw new BusinessException("Impossible de créer le dossier du SoftHSM : " + parent);
                }

                KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
                keyStore.load(null, null);
                try (FileOutputStream out = new FileOutputStream(file))
                {
                    keyStore.store(out, password());
                }
                log.info("[SoftHSM] KeyStore PKCS12 initialisé : {}", file.getAbsolutePath());
            }
            catch (Exception e)
            {
                throw new BusinessException("Impossible d'initialiser le SoftHSM", e);
            }
            finally
            {
                lock.writeLock().unlock();
            }
        }
    }

    /** Ouvre le jeton SoftHSM par PKCS#11. Échoue au démarrage, avec un message clair, si le jeton n'est pas prêt. */
    private void initialiserPkcs11()
    {
        try
        {
            String bibliotheque = hsmProperties.getPkcs11Library();
            if (bibliotheque == null || !new File(bibliotheque).isFile())
            {
                throw new BusinessException("Bibliothèque PKCS#11 introuvable : " + bibliotheque
                    + " (SoftHSM est-il installé ? hsm.pkcs11-library / HSM_PKCS11_LIBRARY)");
            }
            char[] pin = pin();

            Provider fournisseur = Security.getProvider("SunPKCS11-" + NOM_FOURNISSEUR_PKCS11);
            if (fournisseur == null)
            {
                Provider base = Security.getProvider("SunPKCS11");
                if (base == null)
                {
                    throw new BusinessException("Le fournisseur Java SunPKCS11 n'est pas disponible dans cette JVM");
                }
                fournisseur = base.configure("--name = " + NOM_FOURNISSEUR_PKCS11 + "\n"
                    + "library = " + bibliotheque + "\n"
                    + "slotListIndex = " + hsmProperties.getPkcs11SlotListIndex() + "\n");
                Security.addProvider(fournisseur);
            }

            KeyStore magasin = KeyStore.getInstance("PKCS11", fournisseur);
            magasin.load(null, pin);
            fournisseurPkcs11 = fournisseur;
            magasinPkcs11 = magasin;
            log.info("[SoftHSM] Jeton PKCS#11 ouvert (bibliothèque {}, slot {})", bibliotheque,
                hsmProperties.getPkcs11SlotListIndex());
        }
        catch (BusinessException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new BusinessException("Impossible d'ouvrir le jeton SoftHSM (jeton initialisé ? code PIN correct ?) : "
                + e.getMessage(), e);
        }
    }

    /**
     * Génère une paire de clés RSA pour cet alias et la dépose, avec son certificat auto-signé. En mode PKCS11 la paire est
     * générée À L'INTÉRIEUR du jeton et la clé privée n'en sort jamais (non exportable) ; en mode PKCS12, elle est générée
     * en mémoire puis déposée dans le fichier. Une clé existante n'est JAMAIS remplacée : sa clé publique est renvoyée.
     *
     * @return la clé publique de l'alias
     */
    public PublicKey provisionnerCle(String alias)
    {
        if (alias == null || alias.isBlank())
        {
            throw new BusinessException("L'alias de la clé du SoftHSM est obligatoire");
        }

        lock.writeLock().lock();
        try
        {
            KeyStore keyStore = loadKeyStore();
            if (!keyStore.containsAlias(alias) && certificatRevoque(alias).isPresent())
            {
                throw new BusinessException("L'alias '" + alias + "' a été révoqué : une nouvelle clé ne peut pas lui être attribuée");
            }
            if (keyStore.containsAlias(alias))
            {
                log.warn("[SoftHSM] Une clé existe déjà sous l'alias '{}' : elle n'est pas remplacée", alias);
                return keyStore.getCertificate(alias).getPublicKey();
            }

            KeyPairGenerator generateur = fournisseurPkcs11 != null
                ? KeyPairGenerator.getInstance("RSA", fournisseurPkcs11)
                : KeyPairGenerator.getInstance("RSA");
            generateur.initialize(TAILLE_CLE_RSA);
            KeyPair paire = generateur.generateKeyPair();

            X509Certificate certificate = selfSignedCertificate(paire, fournisseurPkcs11 != null);
            keyStore.setKeyEntry(alias, paire.getPrivate(), motDePasseCle(), new Certificate[] { certificate });
            persist(keyStore);
            log.info("[SoftHSM] Paire de clés RSA {} générée et déposée sous l'alias '{}'{}", TAILLE_CLE_RSA, alias,
                fournisseurPkcs11 != null ? " (dans le jeton, clé privée non exportable)" : "");
            return certificate.getPublicKey();
        }
        catch (BusinessException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[SoftHSM] Erreur lors de la génération de la clé pour l'alias '{}' : {}", alias, e.getMessage());
            throw new BusinessException("Impossible de générer la clé dans le SoftHSM", e);
        }
        finally
        {
            lock.writeLock().unlock();
        }
    }

    public void storePrivateKey(String alias, KeyPair keyPair)
    {
        if (alias == null || alias.isBlank())
        {
            throw new BusinessException("L'alias de la clé du SoftHSM est obligatoire");
        }

        lock.writeLock().lock();
        try
        {
            KeyStore keyStore = loadKeyStore();

            X509Certificate certificate = selfSignedCertificate(keyPair, false);
            keyStore.setKeyEntry(
                alias,
                keyPair.getPrivate(),
                motDePasseCle(),
                new Certificate[] { certificate }
            );

            persist(keyStore);
            log.info("[SoftHSM] Clé privée déposée dans le SoftHSM sous l'alias '{}'", alias);
        }
        catch (Exception e)
        {
            log.error("[SoftHSM] Erreur lors du dépôt de la clé pour l'alias '{}' : {}", alias, e.getMessage());
            throw new BusinessException("Impossible de déposer la clé privée dans le SoftHSM", e);
        }
        finally
        {
            lock.writeLock().unlock();
        }
    }

    /**
     * Signe un hash SHA-256 (hexadécimal) avec la clé privée déposée sous {@code alias}.
     * La clé privée ne quitte jamais cette méthode.
     *
     * Verrou en LECTURE seule : plusieurs éditeurs peuvent appeler sign()
     * simultanément sans s'attendre les uns les autres (seul un dépôt de
     * clé concurrent les bloquerait, brièvement).
     */
    public String sign(String alias, String sha256Hash)
    {
        lock.readLock().lock();
        try
        {
            KeyStore keyStore = loadKeyStore();

            if (!keyStore.containsAlias(alias))
            {
                throw new BusinessException("Aucune clé trouvée dans le SoftHSM pour l'alias '" + alias + "'");
            }

            Key key = keyStore.getKey(alias, motDePasseCle());
            if (!(key instanceof PrivateKey privateKey))
            {
                throw new BusinessException("L'entrée '" + alias + "' du SoftHSM n'est pas une clé privée");
            }

            // En mode PKCS11 la clé est une référence vers le jeton : la signature est calculée par le jeton.
            Signature signature = fournisseurPkcs11 != null
                ? Signature.getInstance(SIGNATURE_ALGORITHM, fournisseurPkcs11)
                : Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(hexStringToByteArray(sha256Hash));

            return byteArrayToHexString(signature.sign());
        }
        catch (BusinessException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[SoftHSM] Erreur lors de la signature avec l'alias '{}' : {}", alias, e.getMessage());
            throw new BusinessException("Impossible de signer avec la clé du SoftHSM", e);
        }
        finally
        {
            lock.readLock().unlock();
        }
    }

    /**
     * Clés publiques déjà lues dans le KeyStore — une clé d'alias donné n'est jamais remplacée (voir
     * UserService.provisionnerClePki : seulement si l'alias est vide), donc la mémoriser est sûre. Seules les clés
     * TROUVÉES sont mémorisées : un alias absent est relu à chaque fois (il peut être créé ensuite).
     */
    private final ConcurrentHashMap<String, PublicKey> clesPubliques = new ConcurrentHashMap<>();

    /**
     * Clé publique d'un alias, lue dans le certificat du KeyStore — c'est la RACINE DE CONFIANCE de toute
     * vérification de signature : elle ne vient JAMAIS de la base de données (users.pki_public_key), qu'un
     * attaquant ayant accès à PostgreSQL peut réécrire. Vide si l'alias n'existe pas dans ce KeyStore.
     */
    public Optional<PublicKey> clePublique(String alias)
    {
        if (alias == null || alias.isBlank())
        {
            return Optional.empty();
        }
        PublicKey connue = clesPubliques.get(alias);
        if (connue != null)
        {
            return Optional.of(connue);
        }

        lock.readLock().lock();
        try
        {
            Certificate certificat = loadKeyStore().getCertificate(alias);
            if (certificat == null)
            {
                certificat = certificatRevoque(alias).orElse(null);
            }
            if (certificat == null)
            {
                return Optional.empty();
            }
            clesPubliques.put(alias, certificat.getPublicKey());
            return Optional.of(certificat.getPublicKey());
        }
        catch (Exception e)
        {
            log.warn("[SoftHSM] Lecture de la clé publique '{}' impossible : {}", alias, e.getMessage());
            return Optional.empty();
        }
        finally
        {
            lock.readLock().unlock();
        }
    }

    /**
     * Clé publique d'un alias relue DANS le jeton, sans passer par la mémoire : sert au contrôle des clés, qui doit
     * voir un remplacement survenu après la première lecture. Vide si l'alias est absent ou illisible.
     */
    public Optional<PublicKey> clePubliqueRelue(String alias)
    {
        if (alias == null || alias.isBlank())
        {
            return Optional.empty();
        }
        lock.readLock().lock();
        try
        {
            Certificate certificat = loadKeyStore().getCertificate(alias);
            if (certificat == null)
            {
                certificat = certificatRevoque(alias).orElse(null);
            }
            return certificat == null ? Optional.empty() : Optional.of(certificat.getPublicKey());
        }
        catch (Exception e)
        {
            log.warn("[SoftHSM] Relecture de la clé publique '{}' impossible : {}", alias, e.getMessage());
            return Optional.empty();
        }
        finally
        {
            lock.readLock().unlock();
        }
    }

    /** Clé publique d'un alias au format PEM (X.509), pour affichage — vide si l'alias est inconnu. */
    public Optional<String> clePubliquePem(String alias)
    {
        return clePublique(alias).map(cle -> "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cle.getEncoded())
            + "\n-----END PUBLIC KEY-----");
    }

    /**
     * Vérifie une signature produite par {@link #sign} avec la clé publique du KeyStore (jamais celle de la base).
     *
     * @return vide si la clé de confiance est introuvable (alias absent du KeyStore, KeyStore illisible) — l'état
     *         est alors INDÉTERMINÉ, pas "falsifié" ; true/false sinon.
     */
    public Optional<Boolean> verifier(String alias, String sha256Hex, String signatureHex)
    {
        Optional<PublicKey> cle = clePublique(alias);
        if (cle.isEmpty())
        {
            return Optional.empty();
        }
        if (sha256Hex == null || signatureHex == null || sha256Hex.isBlank() || signatureHex.isBlank()
            || sha256Hex.length() % 2 != 0 || signatureHex.length() % 2 != 0
            || !sha256Hex.matches("[0-9a-fA-F]+") || !signatureHex.matches("[0-9a-fA-F]+"))
        {
            return Optional.of(false);
        }
        try
        {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(cle.get());
            signature.update(hexStringToByteArray(sha256Hex));
            return Optional.of(signature.verify(hexStringToByteArray(signatureHex)));
        }
        catch (Exception e)
        {
            return Optional.of(false);
        }
    }

    // ════════════════════════════════════════════════════════
    // Clés secrètes (HMAC) — la clé ne sort jamais de cette classe
    // ════════════════════════════════════════════════════════

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Calcule le HMAC-SHA256 (en hexadécimal) d'un message, avec une clé que l'appelant ne voit jamais. */
    @FunctionalInterface
    public interface CalculHmac
    {
        String hex(String message);
    }

    /**
     * Dépose dans le SoftHSM une clé secrète HMAC-SHA256 de 256 bits sous cet alias, si elle n'y est pas déjà.
     * Une clé existante n'est JAMAIS remplacée (la remplacer rendrait invérifiable tout ce qu'elle a authentifié).
     *
     * @return true si la clé vient d'être créée
     */
    public boolean garantirCleSecrete(String alias)
    {
        if (alias == null || alias.isBlank())
        {
            throw new BusinessException("L'alias de la clé du SoftHSM est obligatoire");
        }
        lock.writeLock().lock();
        try
        {
            KeyStore keyStore = loadKeyStore();
            if (keyStore.containsAlias(alias))
            {
                return false;
            }
            KeyGenerator generateur = fournisseurPkcs11 != null
                ? KeyGenerator.getInstance(HMAC_ALGORITHM, fournisseurPkcs11)
                : KeyGenerator.getInstance(HMAC_ALGORITHM);
            generateur.init(256);
            SecretKey cle = generateur.generateKey();
            keyStore.setEntry(alias, new KeyStore.SecretKeyEntry(cle),
                fournisseurPkcs11 != null ? null : new KeyStore.PasswordProtection(password()));
            persist(keyStore);
            log.info("[SoftHSM] Clé secrète HMAC déposée dans le SoftHSM sous l'alias '{}'", alias);
            return true;
        }
        catch (BusinessException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[SoftHSM] Erreur lors du dépôt de la clé secrète '{}' : {}", alias, e.getMessage());
            throw new BusinessException("Impossible de déposer la clé secrète dans le SoftHSM", e);
        }
        finally
        {
            lock.writeLock().unlock();
        }
    }

    /**
     * Ouvre la clé secrète {@code alias} UNE fois (un seul chargement du KeyStore) et laisse l'action calculer autant
     * de HMAC qu'elle veut : le chaînage de milliers d'entrées ne relit pas le fichier à chaque entrée. La clé n'est
     * jamais remise à l'appelant — seulement le moyen de calculer avec elle, valable le temps de l'action.
     *
     * @throws BusinessException si l'alias est absent ou n'est pas une clé secrète
     */
    public <T> T avecHmac(String alias, Function<CalculHmac, T> action)
    {
        Mac mac;
        lock.readLock().lock();
        try
        {
            KeyStore keyStore = loadKeyStore();
            if (!keyStore.containsAlias(alias))
            {
                throw new BusinessException("Aucune clé secrète trouvée dans le SoftHSM pour l'alias '" + alias + "'");
            }
            Key cle = keyStore.getKey(alias, motDePasseCle());
            if (cle == null || cle instanceof PrivateKey)
            {
                throw new BusinessException("L'entrée '" + alias + "' du SoftHSM n'est pas une clé secrète");
            }
            if (fournisseurPkcs11 != null)
            {
                // Clé du jeton, non exportable : le HMAC est calculé par le jeton.
                mac = Mac.getInstance(HMAC_ALGORITHM, fournisseurPkcs11);
                mac.init(cle);
            }
            else
            {
                mac = Mac.getInstance(HMAC_ALGORITHM);
                mac.init(new SecretKeySpec(cle.getEncoded(), HMAC_ALGORITHM));
            }
        }
        catch (BusinessException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[SoftHSM] Erreur d'ouverture de la clé secrète '{}' : {}", alias, e.getMessage());
            throw new BusinessException("Impossible d'ouvrir la clé secrète du SoftHSM", e);
        }
        finally
        {
            lock.readLock().unlock();
        }

        CalculHmac calcul = message -> byteArrayToHexString(mac.doFinal(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return action.apply(calcul);
    }

    /** Indique si une clé existe déjà pour cet alias dans le SoftHSM. */
    public boolean hasKey(String alias)
    {
        lock.readLock().lock();
        try
        {
            return loadKeyStore().containsAlias(alias);
        }
        catch (Exception e)
        {
            throw new BusinessException("Impossible de consulter le SoftHSM", e);
        }
        finally
        {
            lock.readLock().unlock();
        }
    }

    /**
     * Révoque la clé de signature d'un alias : la CLÉ PRIVÉE est détruite, le certificat auto-signé (donc la clé publique)
     * est conservé. Plus aucune signature n'est possible avec cet alias, mais les signatures déjà produites restent
     * vérifiables ({@link #verifier}, {@link #clePublique}) — c'est la racine de confiance des documents déjà archivés.
     *
     * Fichier PKCS#12 : la clé privée est retirée de l'entrée, le certificat y reste. Jeton PKCS#11 (SoftHSM) : une
     * entrée ne peut pas être amputée de sa seule clé privée ; le certificat est donc d'abord copié dans le fichier des
     * certificats révoqués (hsm.certificats-revoques-path), puis l'entrée du jeton est supprimée. Cet ordre garantit que
     * la clé publique n'est jamais perdue : si le jeton échoue, la révocation est simplement reprise plus tard.
     *
     * Idempotent : si la clé privée n'existe déjà plus (révocation précédente), rien n'est modifié.
     *
     * @return true si une clé privée a été détruite, false si l'alias est absent ou déjà révoqué
     */
    public boolean revoquerCle(String alias)
    {
        if (alias == null || alias.isBlank())
        {
            return false;
        }

        lock.writeLock().lock();
        try
        {
            KeyStore keyStore = loadKeyStore();
            if (!keyStore.containsAlias(alias) || !keyStore.isKeyEntry(alias))
            {
                return false;
            }

            Certificate certificat = keyStore.getCertificate(alias);
            if (certificat == null)
            {
                throw new BusinessException("Le SoftHSM ne contient pas le certificat de l'alias '" + alias
                    + "' : révocation refusée, la clé publique serait perdue");
            }

            if (magasinPkcs11 != null)
            {
                KeyStore revoques = ouvrirCertificatsRevoques();
                if (!revoques.containsAlias(alias))
                {
                    revoques.setCertificateEntry(alias, certificat);
                    ecrireCertificatsRevoques(revoques);
                }
                keyStore.deleteEntry(alias);
            }
            else
            {
                keyStore.deleteEntry(alias);
                keyStore.setCertificateEntry(alias, certificat);
                persist(keyStore);
            }
            log.info("[SoftHSM] Clé privée de l'alias '{}' détruite ; certificat (clé publique) conservé", alias);
            return true;
        }
        catch (BusinessException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[SoftHSM] Erreur lors de la révocation de l'alias '{}' : {}", alias, e.getMessage());
            throw new BusinessException("Impossible de révoquer la clé dans le SoftHSM", e);
        }
        finally
        {
            lock.writeLock().unlock();
        }
    }

    /** Certificat conservé d'une clé révoquée (mode PKCS#11) — vide si l'alias n'a pas été révoqué ou si le fichier n'existe pas. */
    private Optional<Certificate> certificatRevoque(String alias)
    {
        if (magasinPkcs11 == null || hsmProperties.getCertificatsRevoquesPath() == null
            || hsmProperties.getCertificatsRevoquesPath().isBlank()
            || !new File(hsmProperties.getCertificatsRevoquesPath()).isFile())
        {
            return Optional.empty();
        }
        try
        {
            return Optional.ofNullable(ouvrirCertificatsRevoques().getCertificate(alias));
        }
        catch (Exception e)
        {
            log.warn("[SoftHSM] Lecture du certificat révoqué '{}' impossible : {}", alias, e.getMessage());
            return Optional.empty();
        }
    }

    /** Ouvre (ou crée, vide) le fichier des certificats révoqués. Refuse si son chemin n'est pas configuré. */
    private KeyStore ouvrirCertificatsRevoques() throws Exception
    {
        String chemin = hsmProperties.getCertificatsRevoquesPath();
        if (chemin == null || chemin.isBlank())
        {
            throw new BusinessException("hsm.certificats-revoques-path (HSM_CERTIFICATS_REVOQUES_PATH) n'est pas configuré : "
                + "révocation refusée, la clé publique ne pourrait pas être conservée");
        }
        KeyStore magasin = KeyStore.getInstance(KEYSTORE_TYPE);
        File fichier = new File(chemin);
        if (fichier.isFile())
        {
            try (FileInputStream in = new FileInputStream(fichier))
            {
                magasin.load(in, pin());
            }
        }
        else
        {
            magasin.load(null, null);
        }
        return magasin;
    }

    private void ecrireCertificatsRevoques(KeyStore magasin) throws Exception
    {
        File cible = new File(hsmProperties.getCertificatsRevoquesPath());
        File parent = cible.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
        {
            throw new BusinessException("Impossible de créer le dossier des certificats révoqués : " + parent);
        }
        File tmp = new File(parent, cible.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp))
        {
            magasin.store(out, pin());
        }
        Files.move(tmp.toPath(), cible.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Supprime entièrement l'entrée d'un alias (clé privée ET certificat). À réserver à une clé qui n'a jamais signé
     * (compte supprimé sans avoir servi) : sinon les signatures déjà produites ne seraient plus vérifiables, utiliser
     * {@link #revoquerCle}.
     *
     * @return true si une entrée a été supprimée, false si l'alias est absent
     */
    public boolean supprimerCle(String alias)
    {
        if (alias == null || alias.isBlank())
        {
            return false;
        }

        lock.writeLock().lock();
        try
        {
            KeyStore keyStore = loadKeyStore();
            if (!keyStore.containsAlias(alias))
            {
                return false;
            }
            keyStore.deleteEntry(alias);
            persist(keyStore);
            clesPubliques.remove(alias);
            log.info("[SoftHSM] Entrée de l'alias '{}' supprimée (clé privée et certificat)", alias);
            return true;
        }
        catch (Exception e)
        {
            log.error("[SoftHSM] Erreur lors de la suppression de l'alias '{}' : {}", alias, e.getMessage());
            throw new BusinessException("Impossible de supprimer la clé dans le SoftHSM", e);
        }
        finally
        {
            lock.writeLock().unlock();
        }
    }

    // ════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════

    private KeyStore loadKeyStore() throws Exception
    {
        if (magasinPkcs11 != null)
        {
            return magasinPkcs11;
        }
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        try (FileInputStream in = new FileInputStream(keystoreFile()))
        {
            keyStore.load(in, password());
        }
        return keyStore;
    }

    /** Écrit le KeyStore via un fichier temporaire + remplacement atomique pour éviter toute corruption. */
    private void persist(KeyStore keyStore) throws Exception
    {
        if (magasinPkcs11 != null)
        {
            return;   // le jeton écrit lui-même, immédiatement
        }
        File target = keystoreFile();
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");

        try (FileOutputStream out = new FileOutputStream(tmp))
        {
            keyStore.store(out, password());
        }

        Files.move(tmp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private X509Certificate selfSignedCertificate(KeyPair keyPair, boolean cleDansLeJeton) throws Exception
    {
        X500Name dn = new X500Name(hsmProperties.getCertificateDn());
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime()
            + 365L * hsmProperties.getCertificateValidityYears() * 24 * 60 * 60 * 1000);
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            dn, serial, notBefore, notAfter, dn, keyPair.getPublic());

        JcaContentSignerBuilder constructeur = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM);
        if (cleDansLeJeton)
        {
            constructeur.setProvider(fournisseurPkcs11);   // la clé privée ne sort pas du jeton : c'est lui qui signe
        }
        ContentSigner signer = constructeur.build(keyPair.getPrivate());

        return new JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(builder.build(signer));
    }

    private File keystoreFile()
    {
        String path = hsmProperties.getKeystorePath();
        if (path == null || path.isBlank())
        {
            throw new BusinessException("hsm.keystore-path (HSM_KEYSTORE_PATH) doit être configuré");
        }
        return new File(path);
    }

    /** Mot de passe d'une entrée : celui du fichier en mode PKCS12 ; aucun en mode PKCS11 (le jeton est ouvert par le PIN). */
    private char[] motDePasseCle()
    {
        return fournisseurPkcs11 != null ? null : password();
    }

    private char[] pin()
    {
        String pin = hsmProperties.getPin();
        if (pin == null || pin.isBlank())
        {
            throw new BusinessException("hsm.pin (HSM_PIN) doit être configuré pour le SoftHSM");
        }
        return pin.toCharArray();
    }

    private char[] password()
    {
        String password = hsmProperties.getKeystorePassword();
        if (password == null || password.isBlank())
        {
            throw new BusinessException("hsm.keystore-password (HSM_KEYSTORE_PASSWORD) doit être configuré");
        }
        return password.toCharArray();
    }

    private String byteArrayToHexString(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
        {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private byte[] hexStringToByteArray(String hex)
    {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
        {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
