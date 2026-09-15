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
 * échecs, bloque toute nouvelle tentative — voir AuthService.authenticate.
 *
 * DEUX compteurs strictement INDÉPENDANTS, l'un par email tenté, l'autre par
 * adresse IP (voir ClientIpResolver) : changer d'IP ne contourne jamais le
 * blocage d'un email déjà bloqué, et changer d'email ne contourne jamais le
 * blocage d'une IP déjà bloquée — chacun est vérifié séparément et suffit à
 * lui seul à refuser la tentative (voir verifierAvantConnexion).
 *
 * Durée du blocage ESCALADÉE (pas fixe) : {@link #PALIERS_BLOCAGE} — 5 min la
 * première fois, puis 15 min, 1h, et 24h pour tout blocage supplémentaire
 * tant que la "mémoire" d'escalade n'a pas expiré (voir FENETRE_ESCALADE, 24h :
 * un email/IP qui reste tranquille aussi longtemps repart au palier de base au
 * prochain incident). Chaque connexion RÉUSSIE efface aussi cette mémoire
 * (voir reinitialiserApresSucces) — un utilisateur qui vient de prouver son
 * identité n'a aucune raison de rester sur un palier élevé.
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
 * décider si la tentative est seulement AUTORISÉE à s'exécuter. Seule
 * l'action manuelle {@link #debloquerAdmin} est journalisée, par l'appelant
 * (voir UserService.deverrouillerConnexion).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService
{
    private final StringRedisTemplate redisTemplate;

    /** Nombre d'échecs avant blocage. */
    private static final int SEUIL_ECHECS = 5;

    /** Fenêtre de comptage des échecs — 5 échecs doivent survenir dans cette fenêtre
     *  glissante pour déclencher le blocage ; au-delà, le compteur repart de zéro. */
    private static final Duration FENETRE_COMPTAGE = Duration.ofMinutes(5);

    /** Paliers de durée de blocage, du 1er incident au 4e et au-delà (le dernier
     *  palier est réutilisé indéfiniment pour tout incident supplémentaire) —
     *  "au moins 5 minutes" dès le premier, plus dissuasif à chaque récidive. */
    private static final Duration[] PALIERS_BLOCAGE = {
        Duration.ofMinutes(5),
        Duration.ofMinutes(15),
        Duration.ofHours(1),
        Duration.ofHours(24),
    };

    /** Durée pendant laquelle le nombre de blocages déjà subis reste compté —
     *  au-delà, un nouvel incident repart au palier de base (5 min) plutôt que
     *  de reprendre l'escalade là où elle s'était arrêtée. */
    private static final Duration FENETRE_ESCALADE = Duration.ofHours(24);

    private static final String PREFIXE_ECHECS_EMAIL = "login:fail:email:";
    private static final String PREFIXE_ECHECS_IP = "login:fail:ip:";
    private static final String PREFIXE_BLOCAGE_EMAIL = "login:lock:email:";
    private static final String PREFIXE_BLOCAGE_IP = "login:lock:ip:";
    private static final String PREFIXE_ESCALADE_EMAIL = "login:lockcount:email:";
    private static final String PREFIXE_ESCALADE_IP = "login:lockcount:ip:";

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
            traiterEchec(cleEchecsEmail(email), cleBlocageEmail(email), cleEscaladeEmail(email),
                "Email " + email);
        }
        if (StringUtils.hasText(ip))
        {
            traiterEchec(cleEchecsIp(ip), cleBlocageIp(ip), cleEscaladeIp(ip),
                "IP " + ip);
        }
    }

    private void traiterEchec(String cleEchecs, String cleBlocage, String cleEscalade, String libellePourLog)
    {
        long echecs = incrementer(cleEchecs, FENETRE_COMPTAGE);
        if (echecs < SEUIL_ECHECS)
        {
            return;
        }

        long blocagesConsecutifs = incrementer(cleEscalade, FENETRE_ESCALADE);
        Duration duree = PALIERS_BLOCAGE[(int) Math.min(blocagesConsecutifs - 1, PALIERS_BLOCAGE.length - 1)];

        redisTemplate.opsForValue().set(cleBlocage, "1", duree);
        log.warn("[LoginAttempt] {} bloqué {} après {} échec(s) — {}e blocage consécutif",
            libellePourLog, formatDuree(duree), echecs, blocagesConsecutifs);
    }

    /**
     * Repart de zéro après une connexion réussie — un utilisateur qui vient de
     * prouver son identité n'a aucune raison de rester pénalisé par ses
     * précédents échecs (ni lui, ni un tiers ayant partagé sa même IP), y
     * compris la mémoire d'escalade : le prochain incident, s'il y en a un,
     * repart au palier de base.
     */
    public void reinitialiserApresSucces(String email, String ip)
    {
        if (StringUtils.hasText(email))
        {
            redisTemplate.delete(cleEchecsEmail(email));
            redisTemplate.delete(cleBlocageEmail(email));
            redisTemplate.delete(cleEscaladeEmail(email));
        }
        if (StringUtils.hasText(ip))
        {
            redisTemplate.delete(cleEchecsIp(ip));
            redisTemplate.delete(cleBlocageIp(ip));
            redisTemplate.delete(cleEscaladeIp(ip));
        }
    }

    /**
     * Levée MANUELLE d'un blocage par un administrateur — voir
     * UserService.deverrouillerConnexion. Volontairement INDÉPENDANTE d'un
     * changement de mot de passe (UserService.updateUser change le mot de
     * passe et invalide la session JWT, mais n'appelle jamais ce service) :
     * un admin peut donc réinitialiser un mot de passe SANS lever le blocage,
     * ou lever le blocage SANS toucher au mot de passe. Efface aussi la
     * mémoire d'escalade, comme un succès — un déblocage humain explicite
     * repart sur la durée de base au prochain incident.
     *
     * @param ip optionnel — un admin qui débloque le COMPTE d'un utilisateur
     *           ne connaît/ne vise en général que son email, pas l'IP depuis
     *           laquelle les échecs sont arrivés (potentiellement partagée
     *           avec d'autres utilisateurs légitimes, ex. un réseau de
     *           bureau) ; null pour ne toucher qu'au blocage par email.
     */
    public void debloquerAdmin(String email, String ip)
    {
        reinitialiserApresSucces(email, ip);
    }

    /** INCR classique + EXPIRE posé UNIQUEMENT au tout premier incrément de la
     *  fenêtre (valeur == 1) : les suivants ne repoussent pas l'expiration, la
     *  fenêtre reste bien glissante sur son point de départ. */
    private long incrementer(String cle, Duration fenetre)
    {
        Long valeur = redisTemplate.opsForValue().increment(cle);
        if (valeur == null)
        {
            return 0;
        }
        if (valeur == 1L)
        {
            redisTemplate.expire(cle, fenetre);
        }
        return valeur;
    }

    private String messageBloque(long ttlSecondes)
    {
        return "Trop de tentatives de connexion échouées. Réessayez dans environ "
            + formatDuree(Duration.ofSeconds(ttlSecondes)) + ".";
    }

    /** minutes en dessous d'1h, heures au-delà — un "1440 minutes" pour le palier
     *  de 24h serait illisible. */
    private String formatDuree(Duration duree)
    {
        long minutes = Math.max(1, (duree.getSeconds() + 59) / 60);
        if (minutes < 60)
        {
            return minutes + " minute" + (minutes > 1 ? "s" : "");
        }
        long heures = (minutes + 59) / 60;
        return heures + " heure" + (heures > 1 ? "s" : "");
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

    private String cleEscaladeEmail(String email)
    {
        return PREFIXE_ESCALADE_EMAIL + email.toLowerCase(Locale.ROOT).trim();
    }

    private String cleEscaladeIp(String ip)
    {
        return PREFIXE_ESCALADE_IP + ip.trim();
    }
}
