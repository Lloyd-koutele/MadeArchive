package made.archive.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.StorageEncryptionProperties;
import made.archive.entite.CleChiffrementReference;
import made.archive.exception.CleChiffrementException;
import made.archive.repository.CleChiffrementReferenceRepository;

/**
 * Vérifie que la clé de chiffrement fournie à l'application est bien celle avec laquelle les archives ont été
 * chiffrées, AVANT toute opération qui en dépend (archivage, lecture, contrôle d'intégrité).
 *
 * Une empreinte à sens unique de la clé (HMAC-SHA256 d'un libellé fixe, avec la clé) est enregistrée en base une
 * fois (voir InitialisationCleChiffrement) ; à chaque usage, l'empreinte de la clé configurée est recalculée
 * (quelques microsecondes) et comparée. Sans cela, un déchiffrement raté est indiscernable d'une vraie altération
 * (GCM échoue dans les deux cas) et une clé erronée marquerait tous les documents comme corrompus.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControleCleChiffrementService
{
    public enum Etat
    {
        VALIDE,
        CLE_ABSENTE,
        CLE_MAL_FORMEE,
        CLE_DIFFERENTE,
        /** Aucune empreinte enregistrée encore (premier démarrage, ou initialisation reportée). Toléré. */
        REFERENCE_NON_INITIALISEE
    }

    private static final byte[] LIBELLE = "madearchive/controle-cle-chiffrement/v1".getBytes(StandardCharsets.UTF_8);

    private final StorageEncryptionProperties proprietes;
    private final CleChiffrementReferenceRepository repository;

    /** La référence est immuable en base : une fois lue, elle ne change plus. */
    private final AtomicReference<String> referenceEnCache = new AtomicReference<>();

    public Etat etat()
    {
        Optional<String> configuree;
        try
        {
            configuree = empreinteDeLaCleConfiguree();
        }
        catch (IllegalArgumentException e)
        {
            return Etat.CLE_MAL_FORMEE;
        }
        if (configuree.isEmpty())
        {
            return Etat.CLE_ABSENTE;
        }

        String reference = reference();
        if (reference == null)
        {
            return Etat.REFERENCE_NON_INITIALISEE;
        }
        return egales(reference, configuree.get()) ? Etat.VALIDE : Etat.CLE_DIFFERENTE;
    }

    /** Lève CleChiffrementException si la clé est absente, mal formée ou différente de celle des archives. */
    public void exigerCleValide()
    {
        Etat etat = etat();
        if (etat == Etat.VALIDE)
        {
            return;
        }
        if (etat == Etat.REFERENCE_NON_INITIALISEE)
        {
            log.warn("[CleChiffrement] Aucune empreinte de référence enregistrée : clé non vérifiée.");
            return;
        }
        throw new CleChiffrementException(message(etat));
    }

    public static String message(Etat etat)
    {
        return switch (etat)
        {
            case CLE_ABSENTE -> "La clé de chiffrement des archives (STORAGE_ENCRYPTION_KEY) n'est pas configurée.";
            case CLE_MAL_FORMEE -> "La clé de chiffrement des archives (STORAGE_ENCRYPTION_KEY) est mal formée : "
                + "elle doit être en Base64 et faire 32 octets.";
            case CLE_DIFFERENTE -> "La clé de chiffrement configurée n'est plus celle utilisée pour archiver les "
                + "documents. Les archives ne sont pas altérées : rétablir la clé d'origine.";
            case REFERENCE_NON_INITIALISEE -> "La référence de la clé de chiffrement n'est pas encore enregistrée.";
            case VALIDE -> "Clé de chiffrement valide.";
        };
    }

    /**
     * Enregistre l'empreinte de la clé configurée comme référence. Sans effet si une référence existe déjà.
     * Refuse si la clé est absente ou mal formée.
     */
    public synchronized void enregistrerReference()
    {
        if (reference() != null)
        {
            return;
        }
        String empreinte = empreinteDeLaCleConfiguree()
            .orElseThrow(() -> new CleChiffrementException(message(Etat.CLE_ABSENTE)));
        CleChiffrementReference ligne = new CleChiffrementReference(
            CleChiffrementReference.ID_UNIQUE, empreinte, Instant.now());
        repository.save(ligne);
        referenceEnCache.set(empreinte);
        log.info("[CleChiffrement] Empreinte de la clé de chiffrement enregistrée comme référence.");
    }

    private String reference()
    {
        String enCache = referenceEnCache.get();
        if (enCache != null)
        {
            return enCache;
        }
        String enBase = repository.findById(CleChiffrementReference.ID_UNIQUE)
            .map(CleChiffrementReference::getEmpreinte).orElse(null);
        if (enBase != null)
        {
            referenceEnCache.set(enBase);
        }
        return enBase;
    }

    /** Vide si la clé est absente ; IllegalArgumentException si elle n'est pas du Base64 de 32 octets. */
    private Optional<String> empreinteDeLaCleConfiguree()
    {
        String base64 = proprietes.getKey();
        if (base64 == null || base64.isBlank())
        {
            return Optional.empty();
        }
        byte[] cle = Base64.getDecoder().decode(base64);
        if (cle.length != 32)
        {
            throw new IllegalArgumentException("La clé doit faire 32 octets");
        }
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cle, "HmacSHA256"));
            return Optional.of(HexFormat.of().formatHex(mac.doFinal(LIBELLE)));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    private static boolean egales(String a, String b)
    {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
