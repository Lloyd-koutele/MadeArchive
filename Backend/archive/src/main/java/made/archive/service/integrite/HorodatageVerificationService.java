package made.archive.service.integrite;

import java.util.Arrays;
import java.util.HexFormat;

import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.tsp.TimeStampToken;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Vérification d'un jeton d'horodatage RFC 3161 déjà stocké : signature du jeton, empreinte qu'il atteste, et
 * ancrage de son certificat (voir TsaAncreService). Jusqu'ici les jetons étaient conservés mais jamais relus.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HorodatageVerificationService
{
    public enum Etat
    {
        /** Pas de jeton. */
        ABSENT,
        /** Signature valide, certificat ancré, empreinte attestée = celle attendue. Seul état probant. */
        ATTESTE_ANCRE,
        /** Signature valide, empreinte conforme, mais certificat jamais vu en direct — ne prouve rien contre
         *  un attaquant capable de fabriquer son propre jeton. Jamais utilisé comme preuve. */
        ATTESTE_NON_ANCRE,
        /** Jeton authentique (ancré) mais attestant une AUTRE empreinte. */
        EMPREINTE_DIFFERENTE,
        /** Illisible, signature invalide, ou certificat non ancré attestant autre chose. */
        INVALIDE
    }

    private final TsaAncreService ancres;

    /**
     * @param token       jeton DER (tel que stocké), peut être null
     * @param hashHexAttendu empreinte SHA-256 (hex) que le jeton est censé attester
     */
    public Etat verifier(byte[] token, String hashHexAttendu)
    {
        if (token == null || token.length == 0)
        {
            return Etat.ABSENT;
        }
        try
        {
            TimeStampToken t = new TimeStampToken(new CMSSignedData(token));
            X509CertificateHolder signataire = TsaAncreService.signataire(t);
            if (signataire == null)
            {
                return Etat.INVALIDE;
            }
            TsaAncreService.validerSignature(t, signataire);

            boolean sha256 = NISTObjectIdentifiers.id_sha256.equals(t.getTimeStampInfo().getHashAlgorithm().getAlgorithm());
            boolean conforme = sha256 && hashHexAttendu != null && hashHexAttendu.length() % 2 == 0
                && Arrays.equals(t.getTimeStampInfo().getMessageImprintDigest(), HexFormat.of().parseHex(hashHexAttendu));
            boolean ancre = ancres.estAncre(t);

            if (conforme)
            {
                return ancre ? Etat.ATTESTE_ANCRE : Etat.ATTESTE_NON_ANCRE;
            }
            return ancre ? Etat.EMPREINTE_DIFFERENTE : Etat.INVALIDE;
        }
        catch (Exception e)
        {
            return Etat.INVALIDE;
        }
    }
}
