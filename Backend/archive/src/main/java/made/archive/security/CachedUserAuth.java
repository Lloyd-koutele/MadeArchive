package made.archive.security;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Forme minimale et sérialisable (JSON) d'un utilisateur, mise en cache par
 * AuthCacheService pour le filtre JWT. Volontairement PAS le mot de passe :
 * ce cache ne sert jamais à l'authentification par mot de passe (voir
 * SecurityConfig.userDetailsService, resté non caché, utilisé par
 * AuthenticationManager pour /api/login) — uniquement à revalider un JWT déjà
 * émis, ce qui ne nécessite jamais le hash du mot de passe.
 */
public record CachedUserAuth(
    UUID id,
    String email,
    String nom,
    String prenom,
    boolean actif,
    Instant sessionInvalidatedAt,
    List<String> roleNames
) implements Serializable
{
}
