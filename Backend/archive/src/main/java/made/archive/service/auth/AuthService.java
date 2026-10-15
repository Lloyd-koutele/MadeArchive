package made.archive.service.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import made.archive.dto.UniteOrganisationnelleDto;
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
import made.archive.security.SessionInvalidationService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;

@Service
@RequiredArgsConstructor
public class AuthService
{

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final UserActiveTokenRepository activeTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final DeviceSessionRepository deviceSessionRepository;
    private final AuditLogService auditLogService;
    private final UniteOrganisationnelleService uniteOrganisationnelleService;
    private final LoginAttemptService loginAttemptService;
    private final ClientIpResolver clientIpResolver;
    private final SessionInvalidationService sessionInvalidationService;

    @Value("${jwt.refresh.expiration}")
    private long refreshExpiration;

    @Value("${jwt.session.max-expiration}")
    private long sessionMaxExpiration;

    /** Un jeton déjà échangé reste accepté ce court instant (deux onglets, requêtes simultanées). */
    private static final long DELAI_GRACE_ROTATION_MS = 30_000;

    private Long uoDe(User user)
    {
        return uniteOrganisationnelleService.getUOActuelleUser(user.getId())
            .map(UniteOrganisationnelleDto::getId)
            .orElse(null);
    }

    @Transactional
    public AuthResponse authenticate(LoginRequest request)
    {
        String ip = clientIpResolver.resolve();

        // Vérifié AVANT toute chose (même avant de savoir si l'email existe) —
        // voir LoginAttemptService.verifierAvantConnexion : un email ou une IP
        // déjà bloqué n'a même pas besoin d'atteindre la vérification des
        // identifiants. Lève une BusinessException (voir AuthController, 429)
        // si l'un des deux est bloqué.
        loginAttemptService.verifierAvantConnexion(request.getEmail(), ip);

        Optional<User> userOpt = userRepository.findByEmail(request.getEmail());

        if (userOpt.isEmpty())
        {
            loginAttemptService.enregistrerEchec(request.getEmail(), ip);
            auditLogService.log(null, AuditAction.LOGIN_ECHOUE,
                "Tentative de connexion avec un email inconnu : " + request.getEmail(), false);
            return AuthResponse.failed("Email ou mot de passe incorrect");
        }

        User user = userOpt.get();

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword()))
        {
            loginAttemptService.enregistrerEchec(request.getEmail(), ip);
            auditLogService.log(user, AuditAction.LOGIN_ECHOUE, AuditCible.UTILISATEUR,
                user.getId().toString(), uoDe(user), "Mot de passe incorrect pour " + user.getEmail(), false);
            return AuthResponse.failed("Email ou mot de passe incorrect");
        }

        if(!user.isActif())
        {
            // Ne compte volontairement PAS comme un échec pour le bruteforce
            // (voir LoginAttemptService.enregistrerEchec) : le compte est déjà
            // bloqué pour une autre raison, ce n'est pas un signal de deviner
            // le mot de passe.
            auditLogService.log(user, AuditAction.LOGIN_ECHOUE, AuditCible.UTILISATEUR,
                user.getId().toString(), uoDe(user),
                "Tentative de connexion sur un compte bloqué : " + user.getEmail(), false);
            return AuthResponse.failed("Accès non autorisé pour ce compte, veuillez contacter l'administrateur");
        }

        loginAttemptService.reinitialiserApresSucces(request.getEmail(), ip);
        auditLogService.log(user, AuditAction.LOGIN_REUSSI, AuditCible.UTILISATEUR,
            user.getId().toString(), uoDe(user), "Connexion réussie : " + user.getEmail(), true);

        return resolveSession(user);
    }

    public AuthResponse logout() 
    {
        return AuthResponse.success("Déconnexion réussie");
    }

    @Data
    public static class LoginRequest 
    {
        private String email;
        private String password;
    }

    @Data
    @AllArgsConstructor
    public static class AuthResponse 
    {
        private boolean success;
        private String message;
        private String token;
        private String refreshToken;
        private UUID userId;
        

        public static AuthResponse success(String token, String refreshToken, UUID userId) {
            return new AuthResponse(true, "Authentification réussie", token, refreshToken, userId);
        }

        public static AuthResponse success(String message) {
            return new AuthResponse(true, message, null, null, null);
        }

        public static AuthResponse failed(String message) {
            return new AuthResponse(false, message, null, null, null);
        }

        
    }

    private AuthResponse resolveSession (User user)
    {
        Instant now = Instant.now();
        String accessToken;

        // Nettoyage des sessions expirées ou révoquées au moment de la connexion
        deviceSessionRepository.deleteExpiredOrRevokedByUser(user, now);

        Optional<UserActiveToken> existSession = activeTokenRepository.findByUser(user);

        if(existSession.isPresent() && existSession.get().getExpiresAt().isAfter(now))
        {
            accessToken = existSession.get().getAccessToken();
        }
        else
        {
            accessToken = jwtService.generateToken(user);
            UserActiveToken uat = existSession.orElse(new UserActiveToken());
            uat.setUser(user);
            uat.setAccessToken(accessToken);
            uat.setExpiresAt(now.plusMillis(jwtService.getExpirationTime()));
            activeTokenRepository.save(uat);
        }

        String refreshToken = UUID.randomUUID().toString();
        Instant expirationAbsolue = now.plusMillis(sessionMaxExpiration);
        DeviceSession device = new DeviceSession();
        device.setUser(user);
        device.setRefreshToken(refreshToken);
        device.setFamilleId(UUID.randomUUID().toString());
        device.setExpirationAbsolue(expirationAbsolue);
        device.setCreeLe(now);
        device.setExpiresAt(plusTot(now.plusMillis(refreshExpiration), expirationAbsolue));
        deviceSessionRepository.save(device);

        return AuthResponse.success(accessToken, refreshToken, user.getId());
    }

    private static Instant plusTot(Instant a, Instant b)
    {
        return a.isBefore(b) ? a : b;
    }

    /**
     * Rotation : chaque renouvellement échange le jeton de rafraîchissement contre un NOUVEAU (l'ancien
     * ne sert plus). Un jeton déjà échangé, présenté après un court délai de grâce, est le signe d'une copie
     * volée ou d'un rejeu : toute la connexion (la famille) est alors révoquée. La connexion a aussi une
     * durée maximale absolue que les renouvellements ne repoussent jamais.
     *
     * noRollbackFor : les révocations ci-dessous doivent survivre à l'exception levée juste après.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse refresh(String refreshToken) 
    {
        DeviceSession device = deviceSessionRepository
            .findByRefreshToken(refreshToken)
            .orElseThrow(() -> new BusinessException("Session invalide"));

        Instant now = Instant.now();
        User user = device.getUser();
        String famille = device.getFamilleId() != null ? device.getFamilleId() : refreshToken;

        // 1. Jeton déjà échangé contre un successeur
        if (device.getRotatedAt() != null)
        {
            if (device.getSuccesseur() != null
                && now.isBefore(device.getRotatedAt().plusMillis(DELAI_GRACE_ROTATION_MS))
                && deviceSessionRepository.findByRefreshTokenAndRevokedFalse(device.getSuccesseur()).isPresent())
            {
                // Délai de grâce : l'autre onglet a déjà fait l'échange, on lui renvoie le même résultat.
                return AuthResponse.success(accessTokenPourRenouvellement(user, now), device.getSuccesseur(), user.getId());
            }

            deviceSessionRepository.revoquerFamille(famille);
            auditLogService.log(user, AuditAction.SESSION_INVALIDEE, AuditCible.UTILISATEUR,
                user.getId().toString(), uoDe(user),
                "Jeton de rafraîchissement déjà échangé présenté de nouveau : connexion révoquée pour " + user.getEmail(),
                false);
            throw new BusinessException("Session invalidée, veuillez vous reconnecter");
        }

        if (device.isRevoked())
        {
            throw new BusinessException("Session invalide");
        }

        // 2. Échéance glissante (inactivité) et durée maximale absolue
        if (device.getExpiresAt().isBefore(now)) 
        {
            device.setRevoked(true);
            deviceSessionRepository.save(device);
            throw new BusinessException("Session expirée, veuillez vous reconnecter");
        }

        Instant expirationAbsolue = device.getExpirationAbsolue() != null ? device.getExpirationAbsolue() : device.getExpiresAt();
        if (!now.isBefore(expirationAbsolue))
        {
            deviceSessionRepository.revoquerFamille(famille);
            throw new BusinessException("Durée maximale de session atteinte, veuillez vous reconnecter");
        }

        // 3. Un compte désactivé, ou dont la session a été invalidée (mot de passe, rôle, UO) après la
        //    création de ce jeton, ne peut plus se renouveler : il doit se reconnecter.
        Instant invalideLe = user.getSessionInvalidatedAt();
        Instant creeLe = device.getCreeLe() != null ? device.getCreeLe() : now;
        if (!Boolean.TRUE.equals(user.isActif()) || (invalideLe != null && invalideLe.isAfter(creeLe)))
        {
            deviceSessionRepository.revoquerFamille(famille);
            throw new BusinessException("Session invalidée, veuillez vous reconnecter");
        }

        // 4. Rotation : nouveau jeton, l'ancien est marqué échangé (il reste en base pour détecter un rejeu)
        String nouveauJeton = UUID.randomUUID().toString();
        DeviceSession successeur = new DeviceSession();
        successeur.setUser(user);
        successeur.setRefreshToken(nouveauJeton);
        successeur.setFamilleId(famille);
        successeur.setExpirationAbsolue(expirationAbsolue);
        successeur.setCreeLe(now);
        successeur.setExpiresAt(plusTot(now.plusMillis(refreshExpiration), expirationAbsolue));

        device.setFamilleId(famille);
        device.setRevoked(true);
        device.setRotatedAt(now);
        device.setSuccesseur(nouveauJeton);

        deviceSessionRepository.save(device);
        deviceSessionRepository.save(successeur);
        deviceSessionRepository.supprimerEchangesAnciens(user, now.minusMillis(refreshExpiration + DELAI_GRACE_ROTATION_MS));

        return AuthResponse.success(accessTokenPourRenouvellement(user, now), nouveauJeton, user.getId());
    }

    /**
     * Jeton d'accès à renvoyer lors d'un renouvellement : celui qu'un autre appareil vient d'obtenir s'il est
     * encore loin de son échéance (au-delà des 2/5 de sa durée), sinon un nouveau.
     */
    private String accessTokenPourRenouvellement(User user, Instant now)
    {
        Optional<UserActiveToken> existing = activeTokenRepository.findByUser(user);
        Instant limiteReutilisation = now.plusMillis(jwtService.getExpirationTime() * 2 / 5);
        if (existing.isPresent() && existing.get().getExpiresAt().isAfter(limiteReutilisation))
        {
            return existing.get().getAccessToken();
        }

        String newAccess = jwtService.generateToken(user);
        UserActiveToken uat = existing.orElse(new UserActiveToken());
        uat.setUser(user);
        uat.setAccessToken(newAccess);
        uat.setExpiresAt(now.plusMillis(jwtService.getExpirationTime()));
        activeTokenRepository.save(uat);

        // Uniquement quand un nouveau token est réellement émis — pas à chaque appel.
        auditLogService.log(user, AuditAction.TOKEN_RAFRAICHI, AuditCible.UTILISATEUR,
            user.getId().toString(), uoDe(user), "Access token renouvelé pour " + user.getEmail(), true);

        return newAccess;
    }

    @Transactional
    public AuthResponse logout(String refreshToken) 
    {
        // 1. On cherche la session de cet appareil précis
        deviceSessionRepository.findByRefreshTokenAndRevokedFalse(refreshToken)
            .ifPresent(device -> {
            
            // 2. Cet appareil passe à "revoked = true" (et toute sa famille de jetons successifs)
            device.setRevoked(true);
            deviceSessionRepository.save(device);
            if (device.getFamilleId() != null)
            {
                deviceSessionRepository.revoquerFamille(device.getFamilleId());
            }

            auditLogService.log(device.getUser(), AuditAction.LOGOUT, AuditCible.UTILISATEUR,
                device.getUser().getId().toString(), uoDe(device.getUser()),
                "Déconnexion : " + device.getUser().getEmail(), true);

            // 3. On vérifie s'il reste d'AUTRES appareils actifs pour cet utilisateur
            List<DeviceSession> remaining = deviceSessionRepository
                .findAllByUserAndRevokedFalse(device.getUser());

            // 4. Dernier appareil connecté : le jeton d'accès est RÉVOQUÉ, pas seulement marqué expiré. Un JWT déjà
            //    émis reste sinon accepté jusqu'à son échéance naturelle (JwtAuthFilter ne lit pas user_active_token) ;
            //    l'invalider fait refuser, dès le prochain appel, tout jeton émis avant cet instant (même copié
            //    ailleurs). Il expire aussi l'enregistrement du jeton actif : la prochaine connexion en génère
            //    un autre, émis après l'invalidation donc accepté.
            if (remaining.isEmpty())
            {
                sessionInvalidationService.invalider(device.getUser(),
                    SessionInvalidationService.RAISON_SESSION_INVALIDEE);
            }
        });

        return AuthResponse.success("Déconnexion réussie");
    }
}
