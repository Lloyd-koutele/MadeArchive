package made.archive.service.document;

import java.time.Instant;
import java.util.List;

import org.springframework.cache.Cache;
import org.springframework.beans.factory.annotation.Value;
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
import made.archive.repository.DocumentRepository;
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
 * Deux régimes distincts :
 *
 *   CONTRÔLE TOTAL (TOUT) : délai de 6 h entre deux contrôles totaux, comme avant (pose le délai dans Redis, voir
 *   RedisCacheConfig.CACHE_FIXITY_COOLDOWN). Il vérifie tout, sans plafond.
 *
 *   CONTRÔLES INTERMÉDIAIRES (TYPES, UO) : possibles À TOUT MOMENT — un administrateur qui soupçonne un document
 *   doit pouvoir le faire revérifier sur-le-champ, sans attendre la fin des 6 h — à condition de ne pas dépasser
 *   fixity.manuel.max-documents (1000 par défaut) documents par demande. Ils ne touchent JAMAIS au délai du
 *   contrôle total : lancer un contrôle intermédiaire ne le repousse ni ne le déclenche. Seule restriction : pas de
 *   seconde demande sur un périmètre dont le contrôle est ENCORE EN COURS (le passage est séquentiel).
 *
 * Chaque demande est consignée dans le journal d'audit, avec le nombre de documents.
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
    private final DocumentRepository documentRepository;

    private static final long DELAI_CONTROLE_TOTAL_SECONDES = 6 * 3600L;

    /** Nombre maximal de documents d'un contrôle ciblé (TYPES, UO). */
    @Value("${fixity.manuel.max-documents:1000}")
    private long maxDocuments;

    public enum Portee { TYPES, UO, TOUT }

    /**
     * Valide la demande (autorité, taille du périmètre, pas de contrôle déjà en cours sur ce périmètre) puis lance
     * la vérification en arrière-plan. Ne renvoie rien d'autre qu'une confirmation de démarrage — voir
     * FixityCheckAsyncExecutor pour le résultat réel (notification du demandeur).
     */
    @Transactional(readOnly = true)
    public void declencher(Portee portee, List<Long> typeDocumentIds, List<Long> uoIds, UserDetails userDetails)
    {
        User demandeur = userRepository.findByEmail(userDetails.getUsername())
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));

        List<String> cles = resoudreClesCooldown(portee, typeDocumentIds, uoIds, demandeur);

        verifierAutorite(portee, cles, demandeur);

        long nombre = compterDocuments(portee, typeDocumentIds, uoIds);
        Cache cache = cacheManager.getCache(RedisCacheConfig.CACHE_FIXITY_COOLDOWN);

        if (portee == Portee.TOUT)
        {
            // Contrôle total : délai de 6 h entre deux contrôles totaux (pas de plafond de documents).
            if (cache != null)
            {
                Instant depuis = cache.get("tout", Instant.class);
                if (cache.get("tout") != null)
                {
                    String reessayer = depuis != null ? depuis.plusSeconds(DELAI_CONTROLE_TOTAL_SECONDES).toString() : "bientôt";
                    throw new BusinessException("Un contrôle total a déjà été déclenché récemment (délai de 6 h entre deux "
                        + "contrôles totaux) — réessayer après " + reessayer + ". Les contrôles ciblés (une unité, quelques "
                        + "types) restent possibles à tout moment.");
                }
                cache.put("tout", Instant.now());
            }
        }
        else
        {
            // Contrôle intermédiaire : à tout moment, mais borné.
            if (nombre > maxDocuments)
            {
                throw new BusinessException("Ce périmètre contient " + nombre + " documents : un contrôle ciblé est limité à "
                    + maxDocuments + ". Réduisez le périmètre (une unité ou quelques types), ou demandez un contrôle total "
                    + "(possible toutes les 6 h). Le contrôle automatique de 3 h vérifie tous les documents.");
            }
            // Réservé tout de suite : une deuxième demande identique, une seconde plus tard, doit voir ce contrôle en
            // cours. La marque est retirée à la fin (FixityCheckAsyncExecutor) ; son TTL n'est qu'un filet de sécurité
            // si l'application s'arrête en plein contrôle. Elle ne modifie pas le délai du contrôle total ("tout").
            if (cache != null)
            {
                List<String> enCours = cles.stream().filter(cle -> cache.get(cle) != null).toList();
                if (!enCours.isEmpty())
                {
                    throw new BusinessException("Un contrôle d'intégrité est déjà en cours sur ce périmètre : "
                        + String.join(", ", enCours) + ". Vous serez notifié à sa fin.");
                }
                Instant maintenant = Instant.now();
                cles.forEach(cle -> cache.put(cle, maintenant));
            }
        }

        String libellePortee = libellePortee(portee, typeDocumentIds, uoIds);
        auditLogService.log(demandeur, AuditAction.FIXITY_CHECK_DEMANDE, AuditCible.DOCUMENT,
            null, null,
            "Contrôle d'intégrité manuel demandé — périmètre : " + libellePortee + " (" + nombre + " document(s))", true);

        // Bean SÉPARÉ (voir sa Javadoc) — un appel direct à une méthode @Async
        // de CE bean-ci serait silencieusement synchrone (auto-invocation,
        // hors de portée du proxy Spring AOP).
        // Les marques « en cours » ne concernent que les contrôles ciblés : le délai du contrôle total reste posé.
        List<String> aLiberer = portee == Portee.TOUT ? List.of() : cles;
        fixityCheckAsyncExecutor.executer(portee, typeDocumentIds, uoIds, libellePortee, demandeur, aLiberer);
    }

    private long compterDocuments(Portee portee, List<Long> typeDocumentIds, List<Long> uoIds)
    {
        return switch (portee)
        {
            case TOUT -> documentRepository.count();
            case TYPES -> documentRepository.compterParTypes(typeDocumentIds);
            case UO -> documentRepository.compterParUnites(uoIds);
        };
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
     */
    private void verifierAutorite(Portee portee, List<String> cles, User demandeur)
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
