package made.archive.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import made.archive.dto.UOParentIdPair;
import made.archive.security.CachedUserAuth;

/**
 * Cache Redis — voir security.AuthCacheService (résolution utilisateur pour le
 * filtre JWT, appelée à CHAQUE requête authentifiée) et
 * organisation.UOTreeCacheService (arbre des UO, lu à quasi chaque listing de
 * documents/projets, écrit seulement à la création/déplacement/suppression
 * d'une UO).
 *
 * Un sérialiseur JSON FORTEMENT TYPÉ par cache (pas un sérialiseur générique
 * partagé) : chaque cache ne contient jamais qu'une seule forme de valeur
 * connue à l'avance (CachedUserAuth, List&lt;UOParentIdPair&gt;), donc pas
 * besoin d'information de type polymorphe embarquée dans le JSON (le
 * mécanisme "@class" des sérialiseurs génériques, qui a aussi une surface de
 * sécurité à surveiller côté désérialisation) — plus simple et plus sûr ici.
 *
 * Volontairement PAS de cache par défaut illimité : chaque cache nommé a son
 * propre TTL, adapté à la fréquence de changement réelle de sa donnée — un
 * cache sans expiration sur de la donnée d'autorisation serait dangereux (voir
 * la Javadoc d'AuthCacheService sur l'éviction précise déjà en place ; le TTL
 * n'est qu'un filet de sécurité en plus, pas le mécanisme principal).
 */
@Configuration
@EnableCaching
public class RedisCacheConfig
{
    /** Résolution utilisateur pour le filtre JWT — voir AuthCacheService. */
    public static final String CACHE_USER_AUTH = "userAuthCache";

    /** Liaisons UO→parent — voir UOTreeCacheService. */
    public static final String CACHE_UO_ARBRE = "uoArbreCache";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory)
    {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        RedisCacheConfiguration userAuthConfig = baseConfig()
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, CachedUserAuth.class)))
            .entryTtl(Duration.ofMinutes(10));

        JavaType listeLiaisons = mapper.getTypeFactory()
            .constructCollectionType(List.class, UOParentIdPair.class);
        RedisCacheConfiguration uoArbreConfig = baseConfig()
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new Jackson2JsonRedisSerializer<List<UOParentIdPair>>(mapper, listeLiaisons)))
            .entryTtl(Duration.ofMinutes(30));

        Map<String, RedisCacheConfiguration> parCache = Map.of(
            CACHE_USER_AUTH, userAuthConfig,
            CACHE_UO_ARBRE, uoArbreConfig
        );

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(baseConfig().entryTtl(Duration.ofMinutes(10)))
            .withInitialCacheConfigurations(parCache)
            .build();
    }

    private RedisCacheConfiguration baseConfig()
    {
        return RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new StringRedisSerializer()))
            .disableCachingNullValues();
    }
}
