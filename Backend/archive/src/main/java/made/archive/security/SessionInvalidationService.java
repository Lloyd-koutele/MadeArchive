package made.archive.security;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import made.archive.entite.User;
import made.archive.repository.UserActiveTokenRepository;
import made.archive.repository.UserRepository;

/**
 * Invalidation forcée d'une session déjà ouverte — un JWT valide en main
 * devient inutilisable dès le prochain appel, sans attendre son expiration
 * naturelle (voir JwtAuthFilter.resolveFailureReason, qui compare son "iat"
 * à sessionInvalidatedAt).
 *
 * Volontairement extrait de UserService (qui portait déjà cette logique en
 * privé pour le blocage de compte et le changement de rôle/mot de passe) :
 * UniteOrganisationnelleService a besoin du même mécanisme pour le transfert
 * d'UO, et UserService dépend déjà de UniteOrganisationnelleService — l'y
 * injecter en retour créerait une dépendance circulaire entre les deux
 * services. Ce service n'a de dépendance vers aucun des deux, les deux
 * peuvent donc s'en servir librement.
 */
@Service
@RequiredArgsConstructor
public class SessionInvalidationService
{
    /** Rôle/mot de passe changé par un admin, ou raison non renseignée (compat. anciens enregistrements). */
    public static final String RAISON_SESSION_INVALIDEE = "SESSION_INVALIDATED";

    /** Transfert vers une autre UO — voir UniteOrganisationnelleService.changerUOUtilisateur. */
    public static final String RAISON_UO_CHANGEE = "UO_CHANGEE";

    private final UserRepository userRepository;
    private final UserActiveTokenRepository activeTokenRepository;
    private final AuthCacheService authCacheService;

    /**
     * Invalide la session de {@code user} et enregistre {@code raison}, pour que
     * JwtAuthFilter puisse renvoyer au client le message adapté (voir
     * SecurityConfig.authenticationEntryPoint) plutôt qu'un message générique.
     */
    @Transactional
    public void invalider(User user, String raison)
    {
        user.setSessionInvalidatedAt(Instant.now());
        user.setSessionInvalidationReason(raison);
        userRepository.save(user);

        activeTokenRepository.findByUser(user).ifPresent(uat -> {
            uat.setExpiresAt(Instant.now());
            activeTokenRepository.save(uat);
        });
        authCacheService.evict(user.getEmail());
    }
}
