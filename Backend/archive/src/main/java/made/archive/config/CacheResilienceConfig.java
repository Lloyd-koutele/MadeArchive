package made.archive.config;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Comportement en cas de panne Redis : DÉGRADÉ, jamais BLOQUANT.
 *
 * Par défaut, Spring propage toute exception de lecture/écriture du cache —
 * ce qui, pour AuthCacheService.resolveUserAuth() (appelée à CHAQUE requête
 * authentifiée via JwtAuthFilter), transformait une simple panne Redis en
 * panne TOTALE de l'authentification (plus personne ne peut rien faire,
 * alors qu'avant l'ajout du cache cette donnée venait directement de
 * Postgres, sans dépendance à Redis) — vu en direct : chaque requête après
 * la connexion échouait avec "Erreur JWT : Unable to connect to Redis",
 * l'utilisateur semblant expulsé immédiatement.
 *
 * Un CacheErrorHandler qui se contente de journaliser fait retomber Spring
 * sur un "cache miss" silencieux à chaque étape (lecture, écriture,
 * éviction) : la méthode @Cacheable s'exécute normalement (requête
 * Postgres), exactement comme si Redis n'avait jamais existé. Seule la
 * performance du cache est perdue pendant la panne, jamais la disponibilité
 * de l'application. S'applique à tous les caches (CACHE_USER_AUTH ET
 * CACHE_UO_ARBRE), pas seulement l'authentification.
 *
 * Doit implémenter CachingConfigurer (pas juste déclarer un @Bean
 * CacheErrorHandler) : Spring Cache ne va chercher un gestionnaire d'erreurs
 * personnalisé que via ce contrat. En contrepartie, cacheManager() DOIT être
 * explicitement redéfini pour renvoyer le RedisCacheManager déjà configuré
 * (voir RedisCacheConfig) — sinon Spring ignorerait ce bean existant et
 * retomberait sur un cache manager par défaut, cassant tout le câblage.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class CacheResilienceConfig implements CachingConfigurer
{
    private final CacheManager redisCacheManager;

    @Override
    public CacheManager cacheManager()
    {
        return redisCacheManager;
    }

    @Override
    public CacheErrorHandler errorHandler()
    {
        return new CacheErrorHandler()
        {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key)
            {
                log.warn("[Cache] Redis injoignable en LECTURE sur '{}' (clé {}) — repli sur la source réelle (Postgres) : {}",
                    cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value)
            {
                log.warn("[Cache] Redis injoignable en ÉCRITURE sur '{}' (clé {}) — non bloquant, réessayé à la prochaine requête : {}",
                    cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key)
            {
                log.warn("[Cache] Redis injoignable en ÉVICTION sur '{}' (clé {}) — non bloquant, le TTL prendra le relais : {}",
                    cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache)
            {
                log.warn("[Cache] Redis injoignable en VIDAGE sur '{}' — non bloquant : {}",
                    cache.getName(), exception.getMessage());
            }
        };
    }
}
