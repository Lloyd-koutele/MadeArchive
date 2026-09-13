package made.archive.service.auth;

import java.time.Duration;
import java.util.Locale;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.exception.BusinessException;

/**
 * Protection contre le bruteforce sur /api/login : après {@value #SEUIL_ECHECS}
 * échecs, bloque toute nouvelle tentative pendant {@value #DUREE_BLOCAGE_MINUTES}
 * minutes — voir AuthService.authenticate.
 *
 * DEUX compteurs strictement INDÉPENDANTS, l'un par email tenté, l'autre par
 * adresse IP (voir ClientIpResolver) : changer d'IP ne contourne jamais le
 * blocage d'un email déjà bloqué, et changer d'email ne contourne jamais le
 * blocage d'une IP déjà bloquée — chacun est vérifié séparément et suffit à
 * lui seul à refuser la tentative (voir verifierAvantConnexion). C'est
 * exactement la demande initiale : bloquer par email ET par IP en parallèle,
 * pas l'un OU l'autre.
 *
 * Redis (même StringRedisTemplate auto-configuré par Spring Boot que
 * spring.data.redis.host/port, voir application.properties — pas besoin d'en
 * déclarer un bean séparé) plutôt qu'un état en mémoire de l'application :
 * cette dernière tourne potentiellement derrière plusieurs instances (et de
 * toute façon perdrait tout blocage à chaque redéploiement), alors que Redis
 * est déjà le composant partagé de la pile pour ce genre d'état éphémère à
 * courte durée de vie — voir RedisCacheConfig.CACHE_FIXITY_COOLDOWN pour le
 * même principe : le TTL Redis EST le mécanisme d'expiration lui-même, pas
 * besoin de comparer des dates en code.
 *
 * Ni les compteurs d'échecs ni les clés de blocage ne sont journalisés dans le
 * journal d'audit : AuthService continue de journaliser chaque tentative
 * (LOGIN_ECHOUE / LOGIN_REUSSI) indépendamment de ce service, qui ne fait que
 * décider si la tentative est seulement AUTORISÉE à s'exécuter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService
{
    private final StringRedisTemplate redisTemplate;

    /** Nombre d'échecs avant blocage. */
    private static final int SEUIL_ECHECS = 5;

    /** Durée du blocage — "au moins 5 minutes" : le TTL Redis garantit ce minimum,
     *  jamais moins (une tentative refusée pendant le blocage ne le prolonge pas). */
    private static final int DUREE_BLOCAGE_MINUTES = 5;
    private static final Duration DUREE_BLOCAGE = Duration.ofMinutes(DUREE_BLOCAGE_MINUTES);

    /** Fenêtre de comptage des échecs — 5 échecs doivent survenir dans cette fenêtre
     *  glissante pour déclencher le blocage ; au-delà, le compteur repart de zéro
     *  (même durée que le blocage lui-même, par simplicité — pas de raison objective
     *  d'avoir une fenêtre différente ici). */
    private static final Duration FENETRE_COMPTAGE = Duration.ofMinutes(5);

    private static final String PREFIXE_ECHECS_EMAIL = "login:fail:email:";
    private static final String PREFIXE_ECHECS_IP = "login:fail:ip:";
    private static final String PREFIXE_BLOCAGE_EMAIL = "login:lock:email:";
    private static final String PREFIXE_BLOCAGE_IP = "login:lock:ip:";

    /**
     * @throws BusinessException si l'email ou l'IP est actuellement bloqué —
     *         à appeler AVANT toute vérification d'identifiants (voir
     *         AuthService.authenticate), pour qu'un compte/IP bloqué ne
     *         serve même pas à distinguer "email inconnu" de "mot de passe
     *         incorrect".
     */
    public void verifierAvantConnexion(String email, String ip)
    {
        Long ttlEmail = StringUtils.hasText(email)
            ? redisTemplate.getExpire(cleBlocageEmail(email))
            : null;
        if (ttlEmail != null && ttlEmail > 0)
        {
            throw new BusinessException(messageBloque(ttlEmail));
        }

        Long ttlIp = StringUtils.hasText(ip)
            ? redisTemplate.getExpire(cleBlocageIp(ip))
            : null;
        if (ttlIp != null && ttlIp > 0)
        {
            throw new BusinessException(messageBloque(ttlIp));
        }
    }

    /**
     * Incrémente les deux compteurs (email + IP) après un échec de connexion —
     * email inconnu ou mot de passe incorrect (voir AuthService.authenticate ;
     * une tentative sur un compte DÉSACTIVÉ n'appelle volontairement PAS cette
     * méthode, le compte étant déjà bloqué pour une autre raison, indépendante
     * d'une éventuelle attaque par force brute).
     */
    public void enregistrerEchec(String email, String ip)
    {
        if (StringUtils.hasText(email))
        {
            long echecs = incrementerAvecFenetre(cleEchecsEmail(email));
            if (echecs >= SEUIL_ECHECS)
            {
                bloquer(cleBlocageEmail(email));
                log.warn("[LoginAttempt] Email {} bloqué {} min après {} échec(s)",
                    email, DUREE_BLOCAGE_MINUTES, echecs);
            }
        }

        if (StringUtils.hasText(ip))
        {
            long echecs = incrementerAvecFenetre(cleEchecsIp(ip));
            if (echecs >= SEUIL_ECHECS)
            {
                bloquer(cleBlocageIp(ip));
                log.warn("[LoginAttempt] IP {} bloquée {} min après {} échec(s)",
                    ip, DUREE_BLOCAGE_MINUTES, echecs);
            }
        }
    }

    /**
     * Repart de zéro après une connexion réussie — un utilisateur qui vient de
     * prouver son identité n'a aucune raison de rester pénalisé par ses
     * précédents échecs (ni lui, ni un tiers ayant partagé sa même IP).
     */
    public void reinitialiserApresSucces(String email, String ip)
    {
        if (StringUtils.hasText(email))
        {
            redisTemplate.delete(cleEchecsEmail(email));
            redisTemplate.delete(cleBlocageEmail(email));
        }
        if (StringUtils.hasText(ip))
        {
            redisTemplate.delete(cleEchecsIp(ip));
            redisTemplate.delete(cleBlocageIp(ip));
        }
    }

    /** INCR classique + EXPIRE posé UNIQUEMENT au tout premier échec de la fenêtre
     *  (échecs == 1) : les échecs suivants ne repoussent pas l'expiration, la
     *  fenêtre reste bien glissante sur son point de départ, pas rallongée à
     *  l'infini par des tentatives répétées. */
    private long incrementerAvecFenetre(String cle)
    {
        Long echecs = redisTemplate.opsForValue().increment(cle);
        if (echecs == null)
        {
            return 0;
        }
        if (echecs == 1L)
        {
            redisTemplate.expire(cle, FENETRE_COMPTAGE);
        }
        return echecs;
    }

    private void bloquer(String cleBlocage)
    {
        redisTemplate.opsForValue().set(cleBlocage, "1", DUREE_BLOCAGE);
    }

    private String messageBloque(long ttlSecondes)
    {
        long minutes = Math.max(1, (ttlSecondes + 59) / 60);
        return "Trop de tentatives de connexion échouées. Réessayez dans environ " + minutes
            + " minute" + (minutes > 1 ? "s" : "") + ".";
    }

    private String cleEchecsEmail(String email)
    {
        return PREFIXE_ECHECS_EMAIL + email.toLowerCase(Locale.ROOT).trim();
    }

    private String cleEchecsIp(String ip)
    {
        return PREFIXE_ECHECS_IP + ip.trim();
    }

    private String cleBlocageEmail(String email)
    {
        return PREFIXE_BLOCAGE_EMAIL + email.toLowerCase(Locale.ROOT).trim();
    }

    private String cleBlocageIp(String ip)
    {
        return PREFIXE_BLOCAGE_IP + ip.trim();
    }
}
