package made.archive.service.integrite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.Security;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TimeStampToken;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import made.archive.config.HorodatageProperties;
import made.archive.config.HsmProperties;

/**
 * Racine de confiance des jetons d'horodatage : les certificats de TSA dont une réponse EN DIRECT a déjà été
 * reçue (jamais lue en base de données).
 *
 * Un jeton RFC 3161 embarque le certificat qui l'a signé : sans ancre, n'importe qui peut fabriquer un jeton
 * valide avec son propre certificat. On retient donc l'empreinte du certificat de chaque réponse obtenue
 * directement auprès du TSA configuré (voir HorodatageService.horodater) dans un fichier posé à côté du KeyStore
 * HSM — même volume, hors de portée d'un attaquant qui n'a que la base. Un jeton lu plus tard n'est tenu pour
 * "ancré" que si son certificat figure dans ce fichier. Le premier jeton reçu est accepté sur la foi du réseau
 * à cet instant (principe "faire confiance à la première utilisation") ; les empreintes peuvent aussi être
 * fournies d'avance (horodatage.ancres-supplementaires).
 */
@Slf4j
@Service
public class TsaAncreService
{
    private final Path fichier;
    private final Set<String> empreintes = ConcurrentHashMap.newKeySet();

    public TsaAncreService(HorodatageProperties props, HsmProperties hsmProps)
    {
        this.fichier = resoudreFichier(props, hsmProps);

        if (props.getAncresSupplementaires() != null)
        {
            for (String e : props.getAncresSupplementaires().split(","))
            {
                if (!e.isBlank())
                {
                    empreintes.add(e.trim().toLowerCase());
                }
            }
        }
        if (fichier != null && Files.isReadable(fichier))
        {
            try
            {
                Files.readAllLines(fichier, StandardCharsets.UTF_8).stream()
                    .map(String::trim).filter(l -> !l.isBlank()).map(String::toLowerCase)
                    .forEach(empreintes::add);
            }
            catch (IOException e)
            {
                log.warn("[TSA-Ancre] Lecture de {} impossible : {}", fichier, e.getMessage());
            }
        }
    }

    private static Path resoudreFichier(HorodatageProperties props, HsmProperties hsmProps)
    {
        if (props.getAncresPath() != null && !props.getAncresPath().isBlank())
        {
            return Path.of(props.getAncresPath());
        }
        if (hsmProps.getKeystorePath() != null && !hsmProps.getKeystorePath().isBlank())
        {
            return Path.of(hsmProps.getKeystorePath()).resolveSibling("tsa-ancres.txt");
        }
        return null;
    }

    /** Signataire du jeton, tel qu'embarqué dedans — null si le jeton n'embarque pas son certificat. */
    static X509CertificateHolder signataire(TimeStampToken token)
    {
        @SuppressWarnings("unchecked")
        Collection<X509CertificateHolder> candidats = token.getCertificates().getMatches(token.getSID());
        return candidats.isEmpty() ? null : candidats.iterator().next();
    }

    /** Vérifie la signature du jeton avec son propre certificat embarqué (validité interne, pas l'ancrage). */
    static void validerSignature(TimeStampToken token, X509CertificateHolder signataire) throws Exception
    {
        if (Security.getProvider("BC") == null)
        {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
        token.validate(new JcaSimpleSignerInfoVerifierBuilder().setProvider("BC").build(signataire));
    }

    static String empreinte(X509CertificateHolder certificat) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificat.getEncoded()));
    }

    /** Le certificat signataire de ce jeton est-il l'un de ceux déjà vus en direct ? */
    public boolean estAncre(TimeStampToken token)
    {
        try
        {
            X509CertificateHolder s = signataire(token);
            return s != null && empreintes.contains(empreinte(s));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Retient le certificat d'un jeton obtenu EN DIRECT d'un TSA. N'enregistre rien si la signature du jeton ne
     * se vérifie pas avec son propre certificat. Best-effort : un échec d'écriture ne fait jamais échouer
     * l'horodatage (le jeton reste simplement "non ancré").
     */
    public void memoriser(TimeStampToken token)
    {
        try
        {
            X509CertificateHolder s = signataire(token);
            if (s == null)
            {
                return;
            }
            validerSignature(token, s);
            String e = empreinte(s);
            if (!empreintes.add(e))
            {
                return;
            }
            if (fichier != null)
            {
                Files.writeString(fichier, e + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            log.info("[TSA-Ancre] Certificat de TSA retenu comme ancre de confiance : {}", e);
        }
        catch (Exception e)
        {
            log.warn("[TSA-Ancre] Certificat de TSA non retenu : {}", e.getMessage());
        }
    }

    /** Pour les tests — nombre d'ancres connues. */
    int nombre()
    {
        return empreintes.size();
    }
}
