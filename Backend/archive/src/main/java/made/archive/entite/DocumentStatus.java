package made.archive.entite;

public enum DocumentStatus
{
    PENDING,
    ACTIVE,
    ACTIVE_WARNING,
    CORRUPTED,
    /**
     * Dans la corbeille — suppression demandée par un éditeur (n'importe quel
     * document, plus seulement un corrompu) OU mis de côté automatiquement en
     * fin de rétention légale, en attente du délai de grâce (voir
     * DocumentService.DELAI_GRACE_CORBEILLE_JOURS) avant purge définitive
     * (voir DocumentRetentionService). Exclu de tout listage/recherche
     * normal, seule la corbeille elle-même
     * (DocumentAccessService.getDocumentsCorbeille) le montre. Restaurable :
     * voir Document.statutAvantCorbeille pour le statut auquel il revient.
     */
    CORBEILLE,
    DELETED
}
