package made.archive.service.document;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.RedisCacheConfig;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeDocument;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;

/**
 * Déclenchement MANUEL du contrôle d'intégrité (fixity check) — par
 * opposition à FixityCheckScheduler, qui vérifie TOUT une fois par jour à
 * 3h du matin. Trois périmètres, du plus étroit au plus large :
 *
 *   TYPES → un ou plusieurs types de documents (ADMIN_UO, dans son autorité)
 *   UO    → une ou plusieurs UO                 (ADMIN_UO, dans son autorité)
 *   TOUT  → l'intégralité du catalogue          (ADMIN uniquement)
 *
 * Anti-rafale : chaque périmètre INDIVIDUEL (un type précis, une UO précise,
 * ou "tout") a son propre cooldown de 6h dans Redis (voir
 * RedisCacheConfig.CACHE_FIXITY_COOLDOWN) — peu importe qui déclenche, la
 * fenêtre est partagée. Si une demande porte sur plusieurs types/UO, TOUS
 * doivent être libres pour que la demande soit acceptée ; ceux déjà en
 * cooldown sont listés dans le message d'erreur.
 *
 * L'exécution elle-même est @Async (potentiellement longue — voir
 * FixityCheckService, aucun traitement par lot) : la requête HTTP répond
 * immédiatement, le demandeur est notifié séparément (NotificationType
 * .FIXITY_CHECK_TERMINE) une fois le contrôle réellement terminé.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FixityCheckTriggerService
{
    private final FixityCheckAsyncExecutor fixityCheckAsyncExecutor;
    private final TypeDocumentRepository typeDocumentRepository;
    private final UniteOrganisationnelleService uniteOrganisationnelleService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final CacheManager cacheManager;

    public enum Portee { TYPES, UO, TOUT }

    /**
     * Valide la demande (autorité + cooldown), pose le cooldown IMMÉDIATEMENT
     * (avant même de démarrer le travail réel) puis lance la vérification en
     * arrière-plan. Ne renvoie rien d'autre qu'une confirmation de
     * démarrage — voir notifierResultat pour le résultat réel.
     */
    @Transactional(readOnly = true)
    public void declencher(Portee portee, List<Long> typeDocumentIds, List<Long> uoIds, UserDetails userDetails)
    {
        User demandeur = userRepository.findByEmail(userDetails.getUsername())
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));

        List<String> cles = resoudreClesCooldown(portee, typeDocumentIds, uoIds, demandeur);

        verifierAutoriteEtCooldown(portee, cles, demandeur);

        // Réservé tout de suite, avant le travail réel — une deuxième demande
        // arrivant une seconde plus tard doit déjà voir le cooldown posé.
        Cache cache = cacheManager.getCache(RedisCacheConfig.CACHE_FIXITY_COOLDOWN);
        Instant maintenant = Instant.now();
        if (cache != null)
        {
            cles.forEach(cle -> cache.put(cle, maintenant));
        }

        String libellePortee = libellePortee(portee, typeDocumentIds, uoIds);
        auditLogService.log(demandeur, AuditAction.FIXITY_CHECK_DEMANDE, AuditCible.DOCUMENT,
            null, null,
            "Contrôle d'intégrité manuel demandé — périmètre : " + libellePortee, true);

        // Bean SÉPARÉ (voir sa Javadoc) — un appel direct à une méthode @Async
        // de CE bean-ci serait silencieusement synchrone (auto-invocation,
        // hors de portée du proxy Spring AOP).
        fixityCheckAsyncExecutor.executer(portee, typeDocumentIds, uoIds, libellePortee, demandeur);
    }

    private List<String> resoudreClesCooldown(Portee portee, List<Long> typeDocumentIds, List<Long> uoIds, User demandeur)
    {
        return switch (portee)
        {
            case TOUT -> List.of("tout");
            case TYPES ->
            {
                if (typeDocumentIds == null || typeDocumentIds.isEmpty())
                {
                    throw new BusinessException("Au moins un type de document est requis pour ce périmètre");
                }
                yield typeDocumentIds.stream().map(id -> "type:" + id).toList();
            }
            case UO ->
            {
                if (uoIds == null || uoIds.isEmpty())
                {
                    throw new BusinessException("Au moins une UO est requise pour ce périmètre");
                }
                yield uoIds.stream().map(id -> "uo:" + id).toList();
            }
        };
    }

    /**
     * Autorité : TOUT est réservé à ROLE_ADMIN ; TYPES/UO exigent que le
     * demandeur ait autorité sur l'UO de CHAQUE type/UO demandé (ADMIN_UO
     * scopé à son périmètre, ADMIN toujours autorisé — voir
     * UniteOrganisationnelleService.aAutoriteSur).
     *
     * Cooldown : vérifié APRÈS l'autorité (pas la peine de révéler qu'un
     * périmètre est en cooldown à quelqu'un qui n'y a de toute façon pas
     * accès).
     */
    private void verifierAutoriteEtCooldown(Portee portee, List<String> cles, User demandeur)
    {
        if (portee == Portee.TOUT && !estAdmin(demandeur))
        {
            throw new BusinessException(
                "La vérification de tout le catalogue est réservée aux administrateurs globaux");
        }

        if (portee == Portee.TYPES)
        {
            for (String cle : cles)
            {
                Long typeId = Long.valueOf(cle.substring("type:".length()));
                TypeDocument type = typeDocumentRepository.findById(typeId)
                    .orElseThrow(() -> new BusinessException("Type de document introuvable : " + typeId));
                Long uoId = type.getUniteOrganisationnelle() != null
                    ? type.getUniteOrganisationnelle().getId() : null;
                if (uoId == null || !uniteOrganisationnelleService.aAutoriteSur(uoId, demandeur))
                {
                    throw new BusinessException(
                        "Vous n'avez pas autorité sur le type \"" + type.getNom() + "\"");
                }
            }
        }

        if (portee == Portee.UO)
        {
            for (String cle : cles)
            {
                Long uoId = Long.valueOf(cle.substring("uo:".length()));
                if (!uniteOrganisationnelleService.aAutoriteSur(uoId, demandeur))
                {
                    throw new BusinessException("Vous n'avez pas autorité sur l'UO " + uoId);
                }
            }
        }

        Cache cache = cacheManager.getCache(RedisCacheConfig.CACHE_FIXITY_COOLDOWN);
        if (cache == null) return;

        List<String> enCooldown = cles.stream().filter(cle -> cache.get(cle) != null).toList();
        if (!enCooldown.isEmpty())
        {
            String detail = enCooldown.stream()
                .map(cle -> {
                    Instant depuis = cache.get(cle, Instant.class);
                    String reessayer = depuis != null
                        ? depuis.plusSeconds(6 * 3600).toString()
                        : "bientôt";
                    return cle + " (réessayer après " + reessayer + ")";
                })
                .collect(Collectors.joining(", "));
            throw new BusinessException(
                "Une vérification a déjà été déclenchée récemment sur ce périmètre (délai de 6h entre deux "
                + "déclenchements manuels, pour éviter de surcharger le service) : " + detail);
        }
    }

    private String libellePortee(Portee portee, List<Long> typeDocumentIds, List<Long> uoIds)
    {
        return switch (portee)
        {
            case TOUT -> "tout le catalogue";
            case TYPES -> "type(s) " + typeDocumentIds;
            case UO -> "UO " + uoIds;
        };
    }

    private boolean estAdmin(User user)
    {
        return user.getRoles() != null
            && user.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.ADMIN);
    }
}
