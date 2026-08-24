package made.archive.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Service pour gérer les opérations PKI :
 * - Génération de paires de clés RSA
 * - Signature de documents (SHA-256)
 * - Vérification de signatures
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PkiService
{
    private static final int KEY_SIZE = 2048;
    private static final String ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /**
     * Génère une paire de clés RSA 2048
     * Retourne {privateKey, publicKey} en format PEM
     */
    public KeyPairData generateKeyPair()
    {
        try
        {
            KeyPair keyPair = generateNativeKeyPair();

            // Convertir en PEM
            String privateKeyPem = encodePrivateKeyToPem(keyPair.getPrivate());
            String publicKeyPem = encodePublicKeyToPem(keyPair.getPublic());

            return new KeyPairData(privateKeyPem, publicKeyPem);
        }
        catch (NoSuchAlgorithmException e)
        {
            log.error("[PKI] Impossible de générer les clés : {}", e.getMessage());
            throw new BusinessException("Impossible de générer les clés RSA", e);
        }
    }

    /**
     * Génère une paire de clés RSA 2048 sous forme d'objet {@link KeyPair} natif.
     * Utilisé quand la clé privée doit être transmise directement à un stockage
     * sécurisé (ex : {@link HsmKeyStoreService}) sans transiter par une chaîne PEM.
     */
    public KeyPair generateNativeKeyPair() throws NoSuchAlgorithmException
    {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(ALGORITHM);
        keyGen.initialize(KEY_SIZE);
        KeyPair keyPair = keyGen.generateKeyPair();

        log.info("[PKI] Paire de clés générée : RSA {}", KEY_SIZE);
        return keyPair;
    }

    /**
     * Signe un SHA-256 avec la clé privée (en format PEM)
     * Retourne la signature en hexadécimal
     */
    public String signHash(String sha256Hash, String privateKeyPem)
    {
        try
        {
            // Décoder la clé privée depuis le PEM
            PrivateKey privateKey = decodePrivateKeyFromPem(privateKeyPem);

            // Créer la signature
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            
            // Signer le hash SHA-256 (déjà en bytes)
            byte[] hashBytes = hexStringToByteArray(sha256Hash);
            signature.update(hashBytes);
            
            byte[] signatureBytes = signature.sign();
            String signatureHex = byteArrayToHexString(signatureBytes);

            log.info("[PKI] Document signé. Signature : {}...", signatureHex.substring(0, 32));
            return signatureHex;
        }
        catch (Exception e)
        {
            log.error("[PKI] Erreur lors de la signature : {}", e.getMessage());
            throw new BusinessException("Impossible de signer le document", e);
        }
    }

    /**
     * Vérifie qu'une signature est valide pour un hash donné
     * Retourne true si valide, false sinon
     */
    public boolean verifySignature(String sha256Hash, String signatureHex, String publicKeyPem)
    {
        try
        {
            // Décoder la clé publique
            PublicKey publicKey = decodePublicKeyFromPem(publicKeyPem);

            // Vérifier la signature
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            
            byte[] hashBytes = hexStringToByteArray(sha256Hash);
            signature.update(hashBytes);
            
            byte[] signatureBytes = hexStringToByteArray(signatureHex);
            boolean isValid = signature.verify(signatureBytes);

            log.info("[PKI] Vérification signature : {}", isValid ? "✓ VALIDE" : "✗ INVALIDE");
            return isValid;
        }
        catch (Exception e)
        {
            log.error("[PKI] Erreur lors de la vérification : {}", e.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════
    // Helpers : Encodage/Décodage PEM
    // ════════════════════════════════════════════════════════

    private String encodePrivateKeyToPem(PrivateKey privateKey)
    {
        byte[] privateKeyBytes = privateKey.getEncoded();
        String base64 = Base64.getEncoder().encodeToString(privateKeyBytes);
        return "-----BEGIN PRIVATE KEY-----\n" +
               splitIntoLines(base64, 64) +
               "\n-----END PRIVATE KEY-----";
    }

    public String encodePublicKeyToPem(PublicKey publicKey)
    {
        byte[] publicKeyBytes = publicKey.getEncoded();
        String base64 = Base64.getEncoder().encodeToString(publicKeyBytes);
        return "-----BEGIN PUBLIC KEY-----\n" +
               splitIntoLines(base64, 64) +
               "\n-----END PUBLIC KEY-----";
    }

    private PrivateKey decodePrivateKeyFromPem(String pem)
            throws Exception
    {
        String base64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");

        byte[] decodedKey = Base64.getDecoder().decode(base64);
        java.security.spec.PKCS8EncodedKeySpec keySpec = 
            new java.security.spec.PKCS8EncodedKeySpec(decodedKey);
        
        KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
        return keyFactory.generatePrivate(keySpec);
    }

    private PublicKey decodePublicKeyFromPem(String pem)
            throws Exception
    {
        String base64 = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");

        byte[] decodedKey = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decodedKey);
        
        KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
        return keyFactory.generatePublic(keySpec);
    }

    private String splitIntoLines(String base64, int lineLength)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base64.length(); i += lineLength)
        {
            int endIndex = Math.min(i + lineLength, base64.length());
            sb.append(base64, i, endIndex).append("\n");
        }
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════
    // Helpers : Conversion Hex ↔ Bytes
    // ════════════════════════════════════════════════════════

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

    // ════════════════════════════════════════════════════════
    // DTO : Résultat de génération de clés
    // ════════════════════════════════════════════════════════

    public static class KeyPairData
    {
        public final String privateKeyPem;
        public final String publicKeyPem;

        public KeyPairData(String privateKeyPem, String publicKeyPem)
        {
            this.privateKeyPem = privateKeyPem;
            this.publicKeyPem = publicKeyPem;
        }
    }
}