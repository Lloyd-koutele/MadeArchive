package made.archive.service.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import made.archive.config.MinioProperties;
import made.archive.factory.MinioClientFactory;
import okhttp3.Headers;

/**
 * Vérification de l'arrivée d'un fichier dans le stockage juste après son dépôt : présent, de la bonne taille et, par
 * défaut, de même contenu une fois relu. Tout écart doit lever DepotStockageException, ce qui interrompt l'archivage
 * avant la moindre écriture en base.
 */
class MinioStorageServiceVerificationTest
{
    private static final String CLE = "pdfa/Type/2026-10-10/abc/document.pdf";
    private static final byte[] ATTENDU = "contenu chiffré du PDF/A".getBytes(StandardCharsets.UTF_8);

    private MinioClient client;
    private MinioStorageService service;

    @BeforeEach
    void init()
    {
        client = mock(MinioClient.class);
        MinioClientFactory factory = mock(MinioClientFactory.class);
        when(factory.getClient()).thenReturn(client);
        MinioProperties props = new MinioProperties();
        props.setBucket("archives");
        service = new MinioStorageService(factory, props);
        ReflectionTestUtils.setField(service, "modeVerificationDepot", "CONTENU");
    }

    private static StatObjectResponse stat(long taille)
    {
        StatObjectResponse reponse = mock(StatObjectResponse.class);
        when(reponse.size()).thenReturn(taille);
        return reponse;
    }

    private static GetObjectResponse corps(byte[] octets)
    {
        return new GetObjectResponse(Headers.of(), "archives", "", CLE, new ByteArrayInputStream(octets));
    }

    @Test
    @DisplayName("Fichier présent, même taille et même contenu : le dépôt est accepté")
    void depotConforme() throws Exception
    {
        StatObjectResponse st = stat(ATTENDU.length);
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(st);
        GetObjectResponse relu = corps(ATTENDU.clone());
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(relu);

        assertDoesNotThrow(() -> service.verifierDepot(CLE, ATTENDU));
    }

    @Test
    @DisplayName("Fichier absent du stockage après le dépôt : refusé")
    void fichierAbsent() throws Exception
    {
        ErrorResponse reponse = mock(ErrorResponse.class);
        when(reponse.code()).thenReturn("NoSuchKey");
        ErrorResponseException absent = mock(ErrorResponseException.class);
        when(absent.errorResponse()).thenReturn(reponse);
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(absent);

        DepotStockageException e = assertThrows(DepotStockageException.class, () -> service.verifierDepot(CLE, ATTENDU));
        assertTrue(e.getMessage().contains("absent"));
        verify(client, never()).getObject(any(GetObjectArgs.class));
    }

    @Test
    @DisplayName("Taille différente (écriture tronquée) : refusé sans relire le contenu")
    void tailleDifferente() throws Exception
    {
        StatObjectResponse st = stat(ATTENDU.length - 3);
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(st);

        DepotStockageException e = assertThrows(DepotStockageException.class, () -> service.verifierDepot(CLE, ATTENDU));
        assertTrue(e.getMessage().contains("incomplet"));
        verify(client, never()).getObject(any(GetObjectArgs.class));
    }

    @Test
    @DisplayName("Même taille mais contenu différent : refusé")
    void contenuDifferent() throws Exception
    {
        byte[] altere = ATTENDU.clone();
        altere[0] ^= 0x01;
        StatObjectResponse st = stat(ATTENDU.length);
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(st);
        GetObjectResponse relu = corps(altere);
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(relu);

        DepotStockageException e = assertThrows(DepotStockageException.class, () -> service.verifierDepot(CLE, ATTENDU));
        assertTrue(e.getMessage().contains("ne correspond pas"));
    }

    @Test
    @DisplayName("Mode TAILLE : présence et taille seulement, le contenu n'est pas relu")
    void modeTaille() throws Exception
    {
        ReflectionTestUtils.setField(service, "modeVerificationDepot", "TAILLE");
        StatObjectResponse st = stat(ATTENDU.length);
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(st);

        assertDoesNotThrow(() -> service.verifierDepot(CLE, ATTENDU));
        verify(client, never()).getObject(any(GetObjectArgs.class));
    }

    @Test
    @DisplayName("Vérification impossible (erreur réseau) : dans le doute, on refuse")
    void verificationImpossible() throws Exception
    {
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(new RuntimeException("connexion perdue"));

        assertThrows(DepotStockageException.class, () -> service.verifierDepot(CLE, ATTENDU));
    }
}
