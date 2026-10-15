package made.archive.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.DeviceSession;
import made.archive.entite.User;
import made.archive.entite.UserActiveToken;
import made.archive.exception.BusinessException;
import made.archive.repository.DeviceSessionRepository;
import made.archive.repository.UserActiveTokenRepository;
import made.archive.repository.UserRepository;
import made.archive.security.ClientIpResolver;
import made.archive.security.JwtService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;

/**
 * Renouvellement de session : rotation du jeton de rafraîchissement, détection d'un jeton rejoué (copie volée),
 * durée maximale absolue, et refus après invalidation de la session.
 */
class AuthServiceRefreshTest
{
    private static final long REFRESH_MS = 15 * 60_000L;
    private static final long ACCES_MS = 15 * 60_000L;

    private DeviceSessionRepository sessions;
    private UserActiveTokenRepository actifs;
    private JwtService jwt;
    private AuditLogService audit;
    private AuthService service;
    private User user;

    @BeforeEach
    void init()
    {
        sessions = mock(DeviceSessionRepository.class);
        actifs = mock(UserActiveTokenRepository.class);
        jwt = mock(JwtService.class);
        audit = mock(AuditLogService.class);

        service = new AuthService(mock(UserRepository.class), jwt, actifs, mock(PasswordEncoder.class), sessions, audit,
            mock(UniteOrganisationnelleService.class), mock(LoginAttemptService.class), mock(ClientIpResolver.class),
            mock(made.archive.security.SessionInvalidationService.class));
        ReflectionTestUtils.setField(service, "refreshExpiration", REFRESH_MS);
        ReflectionTestUtils.setField(service, "sessionMaxExpiration", 8 * 3_600_000L);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("test@esp.sn");

        when(jwt.getExpirationTime()).thenReturn(ACCES_MS);
        when(jwt.generateToken(user)).thenReturn("nouvel-acces");
        when(actifs.findByUser(user)).thenReturn(Optional.empty());
    }

    private DeviceSession session(String jeton, Instant creeLe, Instant absolue)
    {
        DeviceSession d = new DeviceSession();
        d.setUser(user);
        d.setRefreshToken(jeton);
        d.setFamilleId("famille-1");
        d.setCreeLe(creeLe);
        d.setExpirationAbsolue(absolue);
        d.setExpiresAt(Instant.now().plusSeconds(300));
        when(sessions.findByRefreshToken(jeton)).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    void le_renouvellement_remplace_le_jeton_de_rafraichissement_et_marque_l_ancien_comme_echange()
    {
        DeviceSession ancien = session("ancien", Instant.now().minusSeconds(600), Instant.now().plus(5, ChronoUnit.HOURS));

        var reponse = service.refresh("ancien");

        assertThat(reponse.getToken()).isEqualTo("nouvel-acces");
        assertThat(reponse.getRefreshToken()).isNotEqualTo("ancien").isNotBlank();
        assertThat(ancien.isRevoked()).isTrue();
        assertThat(ancien.getRotatedAt()).isNotNull();
        assertThat(ancien.getSuccesseur()).isEqualTo(reponse.getRefreshToken());
        verify(sessions, org.mockito.Mockito.times(2)).save(any(DeviceSession.class));
    }

    @Test
    void le_successeur_garde_la_meme_famille_et_la_meme_echeance_absolue()
    {
        Instant absolue = Instant.now().plus(5, ChronoUnit.HOURS);
        session("ancien", Instant.now().minusSeconds(600), absolue);
        var capture = org.mockito.ArgumentCaptor.forClass(DeviceSession.class);

        service.refresh("ancien");

        verify(sessions, org.mockito.Mockito.times(2)).save(capture.capture());
        DeviceSession successeur = capture.getAllValues().get(1);
        assertThat(successeur.getFamilleId()).isEqualTo("famille-1");
        assertThat(successeur.getExpirationAbsolue()).isEqualTo(absolue);
        assertThat(successeur.getRotatedAt()).isNull();
    }

    @Test
    void un_jeton_deja_echange_presente_apres_le_delai_de_grace_revoque_toute_la_connexion()
    {
        DeviceSession ancien = session("ancien", Instant.now().minusSeconds(600), Instant.now().plus(5, ChronoUnit.HOURS));
        ancien.setRevoked(true);
        ancien.setRotatedAt(Instant.now().minusSeconds(120));
        ancien.setSuccesseur("successeur");

        assertThatThrownBy(() -> service.refresh("ancien"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("invalidée");

        verify(sessions).revoquerFamille("famille-1");
        verify(audit).log(eq(user), eq(AuditAction.SESSION_INVALIDEE), any(AuditCible.class), anyString(), any(),
            anyString(), eq(false));
    }

    @Test
    void un_jeton_echange_il_y_a_quelques_secondes_renvoie_le_successeur_sans_rien_revoquer()
    {
        DeviceSession ancien = session("ancien", Instant.now().minusSeconds(600), Instant.now().plus(5, ChronoUnit.HOURS));
        ancien.setRevoked(true);
        ancien.setRotatedAt(Instant.now().minusSeconds(5));
        ancien.setSuccesseur("successeur");
        when(sessions.findByRefreshTokenAndRevokedFalse("successeur")).thenReturn(Optional.of(new DeviceSession()));

        var reponse = service.refresh("ancien");

        assertThat(reponse.getRefreshToken()).isEqualTo("successeur");
        verify(sessions, never()).revoquerFamille(anyString());
    }

    @Test
    void la_duree_maximale_absolue_interdit_tout_renouvellement()
    {
        session("ancien", Instant.now().minusSeconds(600), Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.refresh("ancien"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("maximale");

        verify(sessions).revoquerFamille("famille-1");
        verify(sessions, never()).save(any(DeviceSession.class));
    }

    @Test
    void une_session_invalidee_apres_la_creation_du_jeton_ne_peut_plus_se_renouveler()
    {
        session("ancien", Instant.now().minusSeconds(600), Instant.now().plus(5, ChronoUnit.HOURS));
        user.setSessionInvalidatedAt(Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> service.refresh("ancien")).isInstanceOf(BusinessException.class);

        verify(sessions).revoquerFamille("famille-1");
        verify(sessions, never()).save(any(DeviceSession.class));
    }

    @Test
    void un_compte_desactive_ne_peut_plus_se_renouveler()
    {
        session("ancien", Instant.now().minusSeconds(600), Instant.now().plus(5, ChronoUnit.HOURS));
        user.setActif(false);

        assertThatThrownBy(() -> service.refresh("ancien")).isInstanceOf(BusinessException.class);
    }

    @Test
    void un_jeton_inconnu_est_refuse()
    {
        when(sessions.findByRefreshToken("inconnu")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("inconnu")).isInstanceOf(BusinessException.class);
    }

    @Test
    void le_jeton_d_acces_d_un_autre_appareil_encore_loin_de_son_echeance_est_reutilise()
    {
        session("ancien", Instant.now().minusSeconds(600), Instant.now().plus(5, ChronoUnit.HOURS));
        UserActiveToken actif = new UserActiveToken();
        actif.setUser(user);
        actif.setAccessToken("acces-existant");
        actif.setExpiresAt(Instant.now().plusSeconds(13 * 60));
        when(actifs.findByUser(user)).thenReturn(Optional.of(actif));

        assertThat(service.refresh("ancien").getToken()).isEqualTo("acces-existant");
    }
}
