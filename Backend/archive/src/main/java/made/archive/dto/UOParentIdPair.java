package made.archive.dto;

import java.io.Serializable;

/**
 * Forme plate et sérialisable (JSON) d'une liaison UO→parent — voir
 * UOTreeCacheService. Volontairement PAS un Map&lt;Long, List&lt;Long&gt;&gt;
 * mis en cache directement : les clés d'un objet JSON sont toujours des
 * chaînes, donc un Map à clés Long round-tripperait en clés String après
 * désérialisation (bug silencieux : arbre.getOrDefault(idLong, ...) ne
 * trouverait plus jamais rien). Une liste de paires id/parentId n'a pas ce
 * problème — c'est UniteOrganisationnelleService qui reconstruit la map en
 * mémoire à partir de cette liste, à chaque appel (opération locale bon
 * marché, pas de nouvelle requête).
 */
public record UOParentIdPair(Long id, Long parentId) implements Serializable
{
}
