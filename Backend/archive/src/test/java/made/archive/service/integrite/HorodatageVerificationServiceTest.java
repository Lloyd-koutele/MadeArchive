package made.archive.service.integrite;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.util.Date;
import java.util.HexFormat;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import made.archive.config.HorodatageProperties;
import made.archive.config.HsmProperties;
import made.archive.service.integrite.HorodatageVerificationService.Etat;

/**
 * Jetons RFC 3161 fabriqués localement (BouncyCastle) avec un faux TSA : un jeton n'est probant que si le
 * certificat de son TSA a été reçu "en direct" (TsaAncreService), pas simplement parce qu'il est bien formé.
 */
class HorodatageVerificationServiceTest
{
    @TempDir Path dossier;

    private static final String HASH = HexFormat.of().formatHex(sha256("document"));

    @BeforeAll
    static void bc()
    {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static byte[] sha256(String s)
    {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Jeton signé par un TSA fictif dont la clé est générée pour l'occasion. */
    private static TimeStampToken jeton(String hashHex, KeyPair cle) throws Exception
    {
        X500Name dn = new X500Name("CN=Faux TSA");
        Date debut = new Date(System.currentTimeMillis() - 60_000);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(dn, BigInteger.TEN, debut,
            new Date(System.currentTimeMillis() + 86_400_000L), dn, cle.getPublic());
        builder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        ContentSigner signataire = new JcaContentSignerBuilder("SHA256withRSA").build(cle.getPrivate());
        X509CertificateHolder certificat = builder.build(signataire);

        TimeStampTokenGenerator generateur = new TimeStampTokenGenerator(
            new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                .build(signataire, certificat),
            new JcaDigestCalculatorProviderBuilder().build().get(
                new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                    org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256)),
            new ASN1ObjectIdentifier("1.2.3.4.5"));
        generateur.addCertificates(new JcaCertStore(java.util.List.of(certificat)));

        TimeStampRequestGenerator requete = new TimeStampRequestGenerator();
        requete.setCertReq(true);
        TimeStampRequest tsRequest = requete.generate(TSPAlgorithms.SHA256, HexFormat.of().parseHex(hashHex), BigInteger.ONE);
        return generateur.generate(tsRequest, BigInteger.valueOf(42), new Date());
    }

    private static KeyPair cle() throws Exception
    {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private TsaAncreService ancres()
    {
        HorodatageProperties p = new HorodatageProperties();
        p.setAncresPath(dossier.resolve("ancres.txt").toString());
        return new TsaAncreService(p, new HsmProperties());
    }

    @Test
    void unJetonBienFormeMaisDontLeTsaEstInconnuNEstPasProbant() throws Exception
    {
        HorodatageVerificationService service = new HorodatageVerificationService(ancres());
        byte[] token = jeton(HASH, cle()).getEncoded();

        assertThat(service.verifier(token, HASH)).isEqualTo(Etat.ATTESTE_NON_ANCRE);
    }

    @Test
    void apresUneReponseEnDirectLeCertificatDuTsaEstReconnu() throws Exception
    {
        TsaAncreService ancres = ancres();
        HorodatageVerificationService service = new HorodatageVerificationService(ancres);
        KeyPair tsa = cle();

        ancres.memoriser(jeton(HASH, tsa)); // réponse reçue du TSA

        // un AUTRE jeton, du même TSA, lu plus tard (par ex. en base)
        String autre = HexFormat.of().formatHex(sha256("autre document"));
        byte[] tokenEnBase = jeton(autre, tsa).getEncoded();
        assertThat(service.verifier(tokenEnBase, autre)).isEqualTo(Etat.ATTESTE_ANCRE);
        // authentique, mais il atteste un AUTRE document : jeton recyclé depuis un autre enregistrement
        assertThat(service.verifier(tokenEnBase, HASH)).isEqualTo(Etat.EMPREINTE_DIFFERENTE);
    }

    @Test
    void unJetonFabriqueAvecUnCertificatInconnuQuiAttesteAutreChoseEstInvalide() throws Exception
    {
        TsaAncreService ancres = ancres();
        ancres.memoriser(jeton(HASH, cle())); // le vrai TSA
        HorodatageVerificationService service = new HorodatageVerificationService(ancres);

        byte[] faux = jeton(HASH, cle()).getEncoded(); // l'attaquant fabrique le sien

        assertThat(service.verifier(faux, HASH)).isEqualTo(Etat.ATTESTE_NON_ANCRE); // jamais ATTESTE_ANCRE
        assertThat(service.verifier(faux, HexFormat.of().formatHex(sha256("x")))).isEqualTo(Etat.INVALIDE);
    }

    @Test
    void absentEtIllisible()
    {
        HorodatageVerificationService service = new HorodatageVerificationService(ancres());

        assertThat(service.verifier(null, HASH)).isEqualTo(Etat.ABSENT);
        assertThat(service.verifier(new byte[0], HASH)).isEqualTo(Etat.ABSENT);
        assertThat(service.verifier(new byte[] { 1, 2, 3 }, HASH)).isEqualTo(Etat.INVALIDE);
    }

    @Test
    void lesAncresSontConserveesDansUnFichierHorsBase() throws Exception
    {
        TsaAncreService premier = ancres();
        premier.memoriser(jeton(HASH, cle()));
        assertThat(Files.readAllLines(dossier.resolve("ancres.txt"))).hasSize(1);

        // un nouveau démarrage relit le fichier
        assertThat(ancres().nombre()).isEqualTo(1);
    }
}
