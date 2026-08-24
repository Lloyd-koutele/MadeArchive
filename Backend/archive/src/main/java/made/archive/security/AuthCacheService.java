package made.archive.security;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import made.archive.config.RedisCacheConfig;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.User;
import made.archive.repository.UserRepository;

/**
 * Résolution utilisateur pour JwtAuthFilter — appelée à CHAQUE requête
 * authentifiée, donc le point le plus rentable à mettre en cache de toute
 * l'application. Volontairement SÉPARÉE du bean UserDetailsService de
 * SecurityConfig (utilisé par /api/login via AuthenticationManager) : celui-là
 * reste non caché et continue de fournir le vrai hash du mot de passe,
 * nécessaire à la vérification BCrypt — jamais mis en cache ici (voir
 * CachedUserAuth).
 *
 * Éviction : PAS via @CacheEvict sur des méthodes de UserService (la clé —
 * l'email — n'y est connue qu'après avoir déjà chargé l'utilisateur en base,
 * impossible à exprimer en SpEL statique sur les paramètres de méthode).
 * À la place, UserService appelle evict(email) explicitement aux 3 endroits
 * où sessionInvalidatedAt change (blocage, changement de rôle, changement de
 * mot de passe) — voir UserService. Le TTL court (voir RedisCacheConfig) est
 * un filet de sécurité en plus, pas le mécanisme principal.
 */
@Service
@RequiredArgsConstructor
public class AuthCacheService
{
    private final UserRepository userRepository;
    private final CacheManager cacheManager;

    @Cacheable(value = RedisCacheConfig.CACHE_USER_AUTH, key = "#email", unless = "#result == null")
    public CachedUserAuth resolveUserAuth(String email)
    {
        return userRepository.findByEmail(email)
            .map(u -> new CachedUserAuth(
                u.getId(),
                u.getEmail(),
                u.getNom(),
                u.getPrenom(),
                u.isActif(),
                u.getSessionInvalidatedAt(),
                u.getRoles().stream().map(r -> r.getName().name()).toList()
            ))
            .orElse(null);
    }

    /**
     * Reconstruit un UserDetailsImpl fonctionnellement équivalent à celui du
     * bean UserDetailsService, à partir de la forme mise en cache — sans
     * requête base. Le User reconstruit n'a que les champs nécessaires en aval
     * (id, email, nom, prenom, actif, sessionInvalidatedAt, roles) ; ses
     * relations JPA restantes (documents, UO...) restent vides — la plupart du
     * code métier re-résout de toute façon l'utilisateur depuis son id/email
     * (voir les patterns resolveUser(...) déjà en place partout), donc ça ne
     * pose problème que si un appelant lit directement une relation LAZY sur
     * ce principal-là, ce qui n'arrive normalement jamais.
     */
    public UserDetailsImpl toUserDetails(CachedUserAuth cached)
    {
        User user = new User();
        user.setId(cached.id());
        user.setEmail(cached.email());
        user.setNom(cached.nom());
        user.setPrenom(cached.prenom());
        user.setActif(cached.actif());
        user.setSessionInvalidatedAt(cached.sessionInvalidatedAt());

        Set<Role> roles = cached.roleNames().stream()
            .map(nom -> {
                Role r = new Role();
                r.setName(Role_Name.valueOf(nom));
                return r;
            })
            .collect(Collectors.toSet());
        user.setRoles(roles);

        List<SimpleGrantedAuthority> authorities = cached.roleNames().stream()
            .map(nom -> new SimpleGrantedAuthority("ROLE_" + nom))
            .toList();

        return new UserDetailsImpl(user, authorities);
    }

    /**
     * Appelé par UserService partout où sessionInvalidatedAt change (blocage,
     * changement de rôle, changement de mot de passe) — voir Javadoc de la
     * classe.
     */
    public void evict(String email)
    {
        var cache = cacheManager.getCache(RedisCacheConfig.CACHE_USER_AUTH);
        if (cache != null)
        {
            cache.evict(email);
        }
    }
}
