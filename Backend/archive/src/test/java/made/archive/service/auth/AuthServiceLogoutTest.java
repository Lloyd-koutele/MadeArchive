package made.archive.service.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import made.archive.entite.DeviceSession;
import made.archive.entite.User;
import made.archive.repository.DeviceSessionRepository;
import made.archive.repository.UserActiveTokenRepository;
import made.archive.repository.UserRepository;
import made.archive.security.ClientIpResolver;
import made.archive.security.JwtService;
import made.archive.security.SessionInvalidationService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;

/** À la dernière déconnexion d'un compte, son jeton d'accès est révoqué ; tant qu'un autre appareil reste connecté, non. */
class AuthServiceLogoutTest
{
    private DeviceSessionRepository sessions;
    private SessionInvalidationService invalidation;
    private AuthService service;
    private User user;
    private DeviceSession appareil;

    @BeforeEach
    void init()
    {
        sessions = mock(DeviceSessionRepository.class);
        invalidation = mock(SessionInvalidationService.class);
        service = new AuthService(mock(UserRepository.class), mock(JwtService.class), mock(UserActiveTokenRepository.class),
            mock(PasswordEncoder.class), sessions, mock(AuditLogService.class), mock(UniteOrganisationnelleService.class),
            mock(LoginAttemptService.class), mock(ClientIpResolver.class), invalidation);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("ed@x.sn");
        appareil = new DeviceSession();
        appareil.setUser(user);
        appareil.setRefreshToken("jeton-a");
        appareil.setFamilleId("famille-a");
        appareil.setExpiresAt(Instant.now().plusSeconds(600));
        when(sessions.findByRefreshTokenAndRevokedFalse("jeton-a")).thenReturn(Optional.of(appareil));
    }

    @Test
    void dernierAppareil_leJetonDAccesEstRevoque()
    {
        when(sessions.findAllByUserAndRevokedFalse(user)).thenReturn(List.of());

        service.logout("jeton-a");

        verify(sessions).revoquerFamille("famille-a");
        verify(invalidation).invalider(eq(user), any());
    }

    @Test
    void autreAppareilToujoursConnecte_leJetonDAccesN_estPasRevoque()
    {
        when(sessions.findAllByUserAndRevokedFalse(user)).thenReturn(List.of(new DeviceSession()));

        service.logout("jeton-a");

        verify(sessions).revoquerFamille("famille-a");
        verify(invalidation, never()).invalider(any(), any());
    }

    @Test
    void jetonDeRafraichissementInconnu_neRevoqueRien()
    {
        service.logout("inconnu");

        verify(invalidation, never()).invalider(any(), any());
    }
}
