package made.archive.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.StorageEncryptionProperties;
import made.archive.exception.BusinessException;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Chiffrement au repos (AES-256-GCM) des PDF/A avant leur dépôt dans MinIO.
 *
 * Archivage à valeur probante : le hash SHA-256 (pdfaSha256) et la signature
 * PKI de l'éditeur sont TOUJOURS calculés sur le PDF/A EN CLAIR, avant tout
 * chiffrement — encrypt() doit être la toute dernière opération avant l'appel
 * de stockage (StorageService.uploadBytes), et decrypt() la toute première
 * après une lecture depuis le stockage, avant tout autre traitement (hash,
 * OCR, envoi au client).
 *
 * GCM apporte un bénéfice supplémentaire pour l'intégrité : son tag
 * d'authentification fait échouer decrypt() si le chiffré a été altéré, un
 * signal de falsification détecté avant même la comparaison de hash.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentEncryptionService
{
    private static final String ALGORITHM            = "AES/GCM/NoPadding";
    private static final int    GCM_TAG_LENGTH_BITS   = 128;
    private static final int    GCM_IV_LENGTH_BYTES   = 12;

    private final StorageEncryptionProperties properties;
    private final ControleCleChiffrementService controleCle;

    /**
     * Chiffre des octets en clair. Retourne IV (12 octets) + chiffré
     * (tag d'authentification GCM inclus).
     */
    public byte[] encrypt(byte[] plaintext)
    {
        // Jamais de nouvelle archive chiffrée avec une clé qui n'est pas celle des archives existantes.
        controleCle.exigerCleValide();
        try
        {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        }
        catch (Exception e)
        {
            log.error("[Encryption] Erreur de chiffrement : {}", e.getMessage());
            throw new BusinessException("Impossible de chiffrer le document", e);
        }
    }

    /**
     * Déchiffre des octets produits par encrypt(). Lève une BusinessException
     * si le tag d'authentification GCM ne correspond pas (contenu altéré) ou
     * si la clé est invalide.
     *
     * La clé est contrôlée AVANT (ControleCleChiffrementService) : une clé absente ou différente de celle des
     * archives lève une CleChiffrementException (erreur de configuration), distincte de la BusinessException
     * d'un contenu réellement altéré — l'appelant ne doit alors rien conclure sur le fichier.
     */
    public byte[] decrypt(byte[] ivAndCiphertext)
    {
        controleCle.exigerCleValide();
        return dechiffrer(ivAndCiphertext);
    }

    /**
     * Vrai si ces octets se déchiffrent avec la clé configurée, sans contrôle préalable de la clé. Réservé à
     * l'initialisation de la référence (InitialisationCleChiffrement) : on prouve que la clé ouvre une archive
     * existante AVANT d'en enregistrer l'empreinte.
     */
    public boolean peutDechiffrer(byte[] ivAndCiphertext)
    {
        try
        {
            dechiffrer(ivAndCiphertext);
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private byte[] dechiffrer(byte[] ivAndCiphertext)
    {
        if (ivAndCiphertext == null || ivAndCiphertext.length <= GCM_IV_LENGTH_BYTES)
        {
            throw new BusinessException("Contenu chiffré invalide (trop court)");
        }
        try
        {
            byte[] iv         = Arrays.copyOfRange(ivAndCiphertext, 0, GCM_IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(ivAndCiphertext, GCM_IV_LENGTH_BYTES, ivAndCiphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        }
        catch (Exception e)
        {
            log.error("[Encryption] Erreur de déchiffrement (contenu altéré ou clé invalide) : {}",
                e.getMessage());
            throw new BusinessException(
                "Impossible de déchiffrer le document — contenu altéré ou clé invalide", e);
        }
    }

    private SecretKeySpec secretKey()
    {
        String base64Key = properties.getKey();
        if (base64Key == null || base64Key.isBlank())
        {
            throw new BusinessException(
                "storage.encryption.key (STORAGE_ENCRYPTION_KEY) doit être configuré");
        }

        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32)
        {
            throw new BusinessException(
                "STORAGE_ENCRYPTION_KEY doit décoder en 32 octets (AES-256), trouvé : "
                + keyBytes.length);
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
