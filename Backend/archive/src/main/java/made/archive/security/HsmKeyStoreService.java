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
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.locks.ReentrantReadWriteLock;


@Slf4j
@Service
@RequiredArgsConstructor
public class HsmKeyStoreService
{
    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final HsmProperties hsmProperties;

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

        File file = keystoreFile();
        if (!file.exists())
        {
            lock.writeLock().lock();
            try
            {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs())
                {
                    throw new BusinessException("Impossible de créer le dossier du HSM fichier : " + parent);
                }

                KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
                keyStore.load(null, null);
                try (FileOutputStream out = new FileOutputStream(file))
                {
                    keyStore.store(out, password());
                }
                log.info("[HSM] KeyStore PKCS12 initialisé : {}", file.getAbsolutePath());
            }
            catch (Exception e)
            {
                throw new BusinessException("Impossible d'initialiser le HSM fichier", e);
            }
            finally
            {
                lock.writeLock().unlock();
            }
        }
    }

    public void storePrivateKey(String alias, KeyPair keyPair)
    {
        if (alias == null || alias.isBlank())
        {
            throw new BusinessException("L'alias de la clé HSM est obligatoire");
        }

        lock.writeLock().lock();
        try
        {
            KeyStore keyStore = loadKeyStore();

            X509Certificate certificate = selfSignedCertificate(keyPair);
            keyStore.setKeyEntry(
                alias,
                keyPair.getPrivate(),
                password(),
                new Certificate[] { certificate }
            );

            persist(keyStore);
            log.info("[HSM] Clé privée déposée dans le HSM fichier sous l'alias '{}'", alias);
        }
        catch (Exception e)
        {
            log.error("[HSM] Erreur lors du dépôt de la clé pour l'alias '{}' : {}", alias, e.getMessage());
            throw new BusinessException("Impossible de déposer la clé privée dans le HSM fichier", e);
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
                throw new BusinessException("Aucune clé HSM trouvée pour l'alias '" + alias + "'");
            }

            Key key = keyStore.getKey(alias, password());
            if (!(key instanceof PrivateKey privateKey))
            {
                throw new BusinessException("L'entrée '" + alias + "' du HSM fichier n'est pas une clé privée");
            }

            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
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
            log.error("[HSM] Erreur lors de la signature avec l'alias '{}' : {}", alias, e.getMessage());
            throw new BusinessException("Impossible de signer avec la clé du HSM fichier", e);
        }
        finally
        {
            lock.readLock().unlock();
        }
    }

    /** Indique si une clé existe déjà pour cet alias dans le HSM fichier. */
    public boolean hasKey(String alias)
    {
        lock.readLock().lock();
        try
        {
            return loadKeyStore().containsAlias(alias);
        }
        catch (Exception e)
        {
            throw new BusinessException("Impossible de consulter le HSM fichier", e);
        }
        finally
        {
            lock.readLock().unlock();
        }
    }

    // ════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════

    private KeyStore loadKeyStore() throws Exception
    {
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

    private X509Certificate selfSignedCertificate(KeyPair keyPair) throws Exception
    {
        X500Name dn = new X500Name(hsmProperties.getCertificateDn());
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime()
            + 365L * hsmProperties.getCertificateValidityYears() * 24 * 60 * 60 * 1000);
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            dn, serial, notBefore, notAfter, dn, keyPair.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .build(keyPair.getPrivate());

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
