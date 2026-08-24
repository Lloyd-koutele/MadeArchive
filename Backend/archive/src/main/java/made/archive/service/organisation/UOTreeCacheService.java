package made.archive.service.organisation;

import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import made.archive.config.RedisCacheConfig;
import made.archive.dto.UOParentIdPair;
import made.archive.repository.UniteOrganisationnelleRepository;

/**
 * Cache des liaisons UO→parent (une seule requête, lue à quasi chaque
 * listing de documents/projets via UniteOrganisationnelleService — voir
 * getUoIdsVisiblesPourLecture/sousArbreDe/getUtilisateursAutorisesIds).
 *
 * Extrait dans son propre service (plutôt que @Cacheable directement sur
 * UniteOrganisationnelleService.chargerArbreEnfants(), qui est privée) : le
 * cache Spring repose sur un proxy AOP — un appel interne (this.xxx(), y
 * compris implicite) contourne totalement le proxy et donc le cache. Un bean
 * séparé, appelé depuis l'extérieur de sa propre classe, n'a pas ce problème.
 */
@Service
@RequiredArgsConstructor
public class UOTreeCacheService
{
    private final UniteOrganisationnelleRepository uoRepository;

    @Cacheable(RedisCacheConfig.CACHE_UO_ARBRE)
    public List<UOParentIdPair> chargerLiaisons()
    {
        return uoRepository.findAllIdsEtParents().stream()
            .map(p -> new UOParentIdPair(p.getId(), p.getParentId()))
            .toList();
    }

    /**
     * À appeler chaque fois que la STRUCTURE de l'arbre change — création,
     * déplacement (changement de parent) ou suppression d'une UO. Un simple
     * renommage ne change pas la forme de l'arbre, pas besoin d'évincer.
     * Une seule entrée en cache (méthode sans paramètre) : allEntries=true
     * est trivial ici, pas un vrai "vider tout le cache".
     */
    @CacheEvict(value = RedisCacheConfig.CACHE_UO_ARBRE, allEntries = true)
    public void evictArbre()
    {
    }
}
