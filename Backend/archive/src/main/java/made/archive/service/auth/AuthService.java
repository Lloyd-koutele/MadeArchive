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
import made.archive.security.JwtService;
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

    @Value("${jwt.refresh.expiration}")
    private long refreshExpiration;

    /**
     * UO actuelle de l'utilisateur, pour le contexte du journal d'audit — sans ça, les
     * entrées de connexion/déconnexion restent invisibles pour tout ADMIN_UO (un IN SQL
     * ne matche jamais NULL), même pour un membre de sa propre UO. Retourne null pour un
     * ADMIN (non rattaché à une UO) — cas normal, pas une erreur.
     */
    private Long uoDe(User user)
    {
        return uniteOrganisationnelleService.getUOActuelleUser(user.getId())
            .map(UniteOrganisationnelleDto::getId)
            .orElse(null);
    }

    @Transactional
    public AuthResponse authenticate(LoginRequest request) 
    {

        Optional<User> userOpt = userRepository.findByEmail(request.getEmail());

        if (userOpt.isEmpty())
        {
            auditLogService.log(null, AuditAction.LOGIN_ECHOUE,
                "Tentative de connexion avec un email inconnu : " + request.getEmail(), false);
            return AuthResponse.failed("Email ou mot de passe incorrect");
        }

        User user = userOpt.get();

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword()))
        {
            auditLogService.log(user, AuditAction.LOGIN_ECHOUE, AuditCible.UTILISATEUR,
                user.getId().toString(), uoDe(user), "Mot de passe incorrect pour " + user.getEmail(), false);
            return AuthResponse.failed("Email ou mot de passe incorrect");
        }

        if(!user.isActif())
        {
            auditLogService.log(user, AuditAction.LOGIN_ECHOUE, AuditCible.UTILISATEUR,
                user.getId().toString(), uoDe(user),
                "Tentative de connexion sur un compte bloqué : " + user.getEmail(), false);
            return AuthResponse.failed("Accès non autorisé pour ce compte, veuillez contacter l'administrateur");
        }

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
        DeviceSession device = new DeviceSession();
        device.setUser(user);
        device.setRefreshToken(refreshToken);
        device.setExpiresAt(now.plusMillis(refreshExpiration));
        deviceSessionRepository.save(device);

        return AuthResponse.success(accessToken, refreshToken, user.getId());
    }

    @Transactional
    public AuthResponse refresh(String refreshToken) 
    {
        DeviceSession device = deviceSessionRepository
            .findByRefreshTokenAndRevokedFalse(refreshToken)
            .orElseThrow(() -> new BusinessException("Session invalide"));

        Instant now = Instant.now();

        if (device.getExpiresAt().isBefore(now)) {
            device.setRevoked(true);
            deviceSessionRepository.save(device);
            throw new BusinessException("Session expirée, veuillez vous reconnecter");
        }

        
        User user = device.getUser();

        // Vérifier si un autre appareil a déjà renouvelé l'access token
        Optional<UserActiveToken> existing = activeTokenRepository.findByUser(user);
        if (existing.isPresent() && existing.get().getExpiresAt().isAfter(now)) 
        {
            // Access token toujours valide → le retourner tel quel
            return AuthResponse.success(existing.get().getAccessToken(), refreshToken, user.getId());
        }

        // Générer un nouvel access token pour tous les appareils
        String newAccess = jwtService.generateToken(user);
        UserActiveToken uat = existing.orElse(new UserActiveToken());
        uat.setUser(user);
        uat.setAccessToken(newAccess);
        uat.setExpiresAt(now.plusMillis(jwtService.getExpirationTime()));
        activeTokenRepository.save(uat);

        // Uniquement quand un nouveau token est réellement émis — pas à chaque appel
        // (le cas "token encore valide" ci-dessus est trop fréquent pour être du bruit utile).
        auditLogService.log(user, AuditAction.TOKEN_RAFRAICHI, AuditCible.UTILISATEUR,
            user.getId().toString(), uoDe(user), "Access token renouvelé pour " + user.getEmail(), true);

        return AuthResponse.success(newAccess, refreshToken, user.getId());
    }

    @Transactional
    public AuthResponse logout(String refreshToken) 
    {
        // 1. On cherche la session de cet appareil précis
        deviceSessionRepository.findByRefreshTokenAndRevokedFalse(refreshToken)
            .ifPresent(device -> {
            
            // 2. Cet appareil passe à "revoked = true"
            device.setRevoked(true);
            deviceSessionRepository.save(device);

            auditLogService.log(device.getUser(), AuditAction.LOGOUT, AuditCible.UTILISATEUR,
                device.getUser().getId().toString(), uoDe(device.getUser()),
                "Déconnexion : " + device.getUser().getEmail(), true);

            // 3. On vérifie s'il reste d'AUTRES appareils actifs pour cet utilisateur
            List<DeviceSession> remaining = deviceSessionRepository
                .findAllByUserAndRevokedFalse(device.getUser());

            // 💡 4. Si la liste est vide (c'était le tout dernier appareil connecté)
            if (remaining.isEmpty()) 
            {
                activeTokenRepository.findByUser(device.getUser())
                    .ifPresent(activeToken -> {
                    activeToken.setExpiresAt(Instant.now());
                    activeTokenRepository.save(activeToken);});
            }
        });

        return AuthResponse.success("Déconnexion réussie");
    }
}
