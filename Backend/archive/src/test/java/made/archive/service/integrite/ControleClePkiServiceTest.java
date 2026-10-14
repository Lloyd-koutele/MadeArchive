package made.archive.service.integrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import made.archive.entite.PkiKeyStatus;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.UserRepository;
import made.archive.security.HsmKeyStoreService;
import made.archive.security.PkiService;

/** La clé d'un éditeur doit rester celle qui a été créée pour lui : sinon on refuse de signer et on alerte. */
class ControleClePkiServiceTest
{
    private static final String HASH = "ab".repeat(32);

    private HsmKeyStoreService hsm;
    private UserRepository userRepository;
    private AlerteIntegriteService alertes;
    private PkiService pki;
    private ControleClePkiService service;

    private PkiService.KeyPairData paire;     // la vraie paire de l'éditeur (celle du jeton)
    private PkiService.KeyPairData autrePaire; // une paire étrangère (jeton modifié)
    private User editeur;

    @BeforeEach
    void init()
    {
        hsm = mock(HsmKeyStoreService.class);
        userRepository = mock(UserRepository.class);
        alertes = mock(AlerteIntegriteService.class);
        pki = new PkiService();
        service = new ControleClePkiService(hsm, pki, userRepository, alertes);

        paire = pki.generateKeyPair();
        autrePaire = pki.generateKeyPair();
        editeur = new User();
        editeur.setId(UUID.randomUUID());
        editeur.setEmail("ed@x.sn");
        editeur.setPkiKeyAlias("editor-1");
        editeur.setPkiKeyStatus(PkiKeyStatus.ACTIVE);
        editeur.setPkiPublicKey(paire.publicKeyPem);
    }

    private PublicKey cle(String pem) throws Exception
    {
        String b64 = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    @Test
    void signerEtControler_cleConforme_renvoieLaSignatureSansAlerte() throws Exception
    {
        when(hsm.clePubliqueRelue("editor-1")).thenReturn(Optional.of(cle(paire.publicKeyPem)));
        when(hsm.sign(eq("editor-1"), anyString())).thenAnswer(i -> pki.signHash(i.getArgument(1), paire.privateKeyPem));

        String signature = service.signerEtControler(editeur, HASH);

        assertThat(pki.verifySignature(HASH, signature, paire.publicKeyPem)).isTrue();
        verify(alertes, never()).clePkiAnormale(anyString());
    }

    @Test
    void signerEtControler_cleDuJetonRemplacee_refuseEtAlerte() throws Exception
    {
        when(hsm.clePubliqueRelue("editor-1")).thenReturn(Optional.of(cle(paire.publicKeyPem)));
        when(hsm.sign(eq("editor-1"), anyString())).thenAnswer(i -> pki.signHash(i.getArgument(1), autrePaire.privateKeyPem));

        assertThatThrownBy(() -> service.signerEtControler(editeur, HASH))
            .isInstanceOf(BusinessException.class).hasMessageContaining("archivage refusé");
        verify(alertes).clePkiAnormale(org.mockito.ArgumentMatchers.contains("ed@x.sn"));
    }

    @Test
    void signerEtControler_certificatDuJetonRemplace_cleSecreteIntacte_refuseSansMemeSigner() throws Exception
    {
        // seul le certificat (clé publique) du jeton a été remplacé : la signature, elle, resterait valide
        when(hsm.clePubliqueRelue("editor-1")).thenReturn(Optional.of(cle(autrePaire.publicKeyPem)));
        when(hsm.sign(eq("editor-1"), anyString())).thenAnswer(i -> pki.signHash(i.getArgument(1), paire.privateKeyPem));

        assertThatThrownBy(() -> service.signerEtControler(editeur, HASH))
            .isInstanceOf(BusinessException.class).hasMessageContaining("archivage refusé");
        verify(hsm, never()).sign(anyString(), anyString());
        verify(alertes).clePkiAnormale(org.mockito.ArgumentMatchers.contains("diffère"));
    }

    @Test
    void signerEtControler_cleAbsenteDuJeton_refuse()
    {
        when(hsm.clePubliqueRelue("editor-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.signerEtControler(editeur, HASH)).isInstanceOf(BusinessException.class);
        verify(hsm, never()).sign(anyString(), anyString());
    }

    @Test
    void signerEtControler_referenceEnBaseAlteree_refuse() throws Exception
    {
        editeur.setPkiPublicKey(autrePaire.publicKeyPem);
        when(hsm.clePubliqueRelue("editor-1")).thenReturn(Optional.of(cle(paire.publicKeyPem)));
        when(hsm.sign(eq("editor-1"), anyString())).thenAnswer(i -> pki.signHash(i.getArgument(1), paire.privateKeyPem));

        assertThatThrownBy(() -> service.signerEtControler(editeur, HASH)).isInstanceOf(BusinessException.class);
    }

    @Test
    void signerEtControler_sansReferenceEnBase_refuse()
    {
        editeur.setPkiPublicKey(null);
        when(hsm.sign(eq("editor-1"), anyString())).thenAnswer(i -> pki.signHash(i.getArgument(1), paire.privateKeyPem));

        assertThatThrownBy(() -> service.signerEtControler(editeur, HASH)).isInstanceOf(BusinessException.class);
    }

    @Test
    void signerEtControler_alerteUneSeuleFoisParEditeurDansLInterval() throws Exception
    {
        when(hsm.clePubliqueRelue("editor-1")).thenReturn(Optional.of(cle(paire.publicKeyPem)));
        when(hsm.sign(eq("editor-1"), anyString())).thenAnswer(i -> pki.signHash(i.getArgument(1), autrePaire.privateKeyPem));

        for (int i = 0; i < 3; i++)
        {
            assertThatThrownBy(() -> service.signerEtControler(editeur, HASH)).isInstanceOf(BusinessException.class);
        }
        verify(alertes, times(1)).clePkiAnormale(anyString());
    }

    @Test
    void balayer_toutEstConforme_renvoieVide() throws Exception
    {
        when(userRepository.findByPkiKeyStatus(PkiKeyStatus.ACTIVE)).thenReturn(List.of(editeur));
        when(hsm.clePubliqueRelue("editor-1")).thenReturn(Optional.of(cle(paire.publicKeyPem)));
        when(hsm.sign(eq("editor-1"), anyString())).thenAnswer(i -> pki.signHash(i.getArgument(1), paire.privateKeyPem));

        assertThat(service.balayer()).isEmpty();
    }

    @Test
    void balayer_clePubliqueDuJetonDifferente_signaleLEditeur() throws Exception
    {
        when(userRepository.findByPkiKeyStatus(PkiKeyStatus.ACTIVE)).thenReturn(List.of(editeur));
        when(hsm.clePubliqueRelue("editor-1")).thenReturn(Optional.of(cle(autrePaire.publicKeyPem)));

        assertThat(service.balayer()).singleElement().asString().contains("ed@x.sn").contains("diffère");
    }

    @Test
    void balayer_clePriveeQuiNeCorrespondPlus_signaleLEditeur() throws Exception
    {
        when(userRepository.findByPkiKeyStatus(PkiKeyStatus.ACTIVE)).thenReturn(List.of(editeur));
        when(hsm.clePubliqueRelue("editor-1")).thenReturn(Optional.of(cle(paire.publicKeyPem)));
        when(hsm.sign(eq("editor-1"), anyString())).thenAnswer(i -> pki.signHash(i.getArgument(1), autrePaire.privateKeyPem));

        assertThat(service.balayer()).singleElement().asString().contains("clé privée");
    }

    @Test
    void balayer_cleAbsenteDuJeton_signalee()
    {
        when(userRepository.findByPkiKeyStatus(PkiKeyStatus.ACTIVE)).thenReturn(List.of(editeur));
        when(hsm.clePubliqueRelue("editor-1")).thenReturn(Optional.empty());

        assertThat(service.balayer()).singleElement().asString().contains("absente du jeton");
    }

    @Test
    void balayer_ecartPassagerPuisRetabli_nEstPasSignale() throws Exception
    {
        when(userRepository.findByPkiKeyStatus(PkiKeyStatus.ACTIVE)).thenReturn(List.of(editeur));
        // première lecture : état intermédiaire (clé pas encore visible) ; deuxième lecture : conforme
        when(hsm.clePubliqueRelue("editor-1")).thenReturn(Optional.empty(), Optional.of(cle(paire.publicKeyPem)));
        when(hsm.sign(eq("editor-1"), anyString())).thenAnswer(i -> pki.signHash(i.getArgument(1), paire.privateKeyPem));

        assertThat(service.balayer()).isEmpty();
    }
}
