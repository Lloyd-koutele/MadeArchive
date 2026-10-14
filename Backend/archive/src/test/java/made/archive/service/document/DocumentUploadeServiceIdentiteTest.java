package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;

import made.archive.dto.DocumentUploadDto;
import made.archive.dto.FinalizeUploadRequestDto;
import made.archive.entite.User;
import made.archive.exception.AccessDeniedException;
import made.archive.repository.UserRepository;
import made.archive.security.HsmKeyStoreService;

/** L'éditeur dont la clé signe est celui qui est connecté — jamais un identifiant lu dans la requête. */
@ExtendWith(MockitoExtension.class)
class DocumentUploadeServiceIdentiteTest
{
    @Mock UserRepository userRepository;
    @Mock OcrSessionCache ocrSessionCache;
    @Mock HsmKeyStoreService hsmKeyStoreService;
    @InjectMocks DocumentUploadeService service;

    private User connecte;
    private UserDetails details;

    @BeforeEach
    void init()
    {
        connecte = new User();
        connecte.setId(UUID.randomUUID());
        connecte.setEmail("ed@x.sn");
        details = mock(UserDetails.class);
        when(details.getUsername()).thenReturn("ed@x.sn");
        when(userRepository.findByEmail("ed@x.sn")).thenReturn(Optional.of(connecte));
    }

    private FinalizeUploadRequestDto requete(UUID sessionId, UUID uploadedById)
    {
        DocumentUploadDto dto = new DocumentUploadDto();
        dto.setUploadedById(uploadedById);
        FinalizeUploadRequestDto r = new FinalizeUploadRequestDto();
        r.setSessionId(sessionId.toString());
        r.setDocumentUploadDto(dto);
        return r;
    }

    @Test
    void editeurConnecte_sansIdentifiantRevendique_renvoieLUtilisateurConnecteLuEnBase()
    {
        assertThat(service.editeurConnecte(details, null)).isSameAs(connecte);
    }

    @Test
    void editeurConnecte_identifiantRevendiqueIdentique_accepte()
    {
        assertThat(service.editeurConnecte(details, connecte.getId())).isSameAs(connecte);
    }

    @Test
    void editeurConnecte_identifiantDUnAutreEditeur_refuse()
    {
        assertThatThrownBy(() -> service.editeurConnecte(details, UUID.randomUUID()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void finalizeUpload_identifiantDUnAutreEditeur_refuseAvantToutAccesALaSessionOuALaCle()
    {
        FinalizeUploadRequestDto r = requete(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> service.finalizeUpload(r, details)).isInstanceOf(AccessDeniedException.class);

        verify(ocrSessionCache, never()).getSession(org.mockito.ArgumentMatchers.any());
        verify(hsmKeyStoreService, never()).sign(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void finalizeUpload_sessionCreeeParUnAutreEditeur_refusee()
    {
        UUID sessionId = UUID.randomUUID();
        OcrSessionCache.OcrSessionData session = new OcrSessionCache.OcrSessionData();
        session.proprietaireId = UUID.randomUUID();
        when(ocrSessionCache.getSession(sessionId)).thenReturn(session);

        FinalizeUploadRequestDto r = requete(sessionId, connecte.getId());

        assertThatThrownBy(() -> service.finalizeUpload(r, details)).isInstanceOf(AccessDeniedException.class);
        verify(hsmKeyStoreService, never()).sign(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void finalizeUpload_sansIdentifiantDansLaRequete_utiliseLeConnecte()
    {
        UUID sessionId = UUID.randomUUID();
        when(ocrSessionCache.getSession(sessionId)).thenReturn(null);
        FinalizeUploadRequestDto r = requete(sessionId, null);

        // La session est absente : le traitement s'arrête là, mais l'identifiant a déjà été posé par le serveur.
        assertThatThrownBy(() -> service.finalizeUpload(r, details)).isInstanceOf(made.archive.exception.BusinessException.class);
        assertThat(r.getDocumentUploadDto().getUploadedById()).isEqualTo(connecte.getId());
    }
}
