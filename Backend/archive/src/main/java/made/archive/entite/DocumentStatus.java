package made.archive.entite;

public enum DocumentStatus
{
    PENDING,
    ACTIVE,
    ACTIVE_WARNING,
    CORRUPTED,
    /**
     * Dans la corbeille — suppression demandée par un éditeur (n'importe quel
     * document, plus seulement un corrompu), en attente du délai de grâce de
     * 3 jours avant purge définitive (voir DocumentRetentionService). Exclu
     * de tout listage/recherche normal, seule la corbeille elle-même
     * (DocumentAccessService.getDocumentsCorbeille) le montre. Restaurable :
     * voir Document.statutAvantCorbeille pour le statut auquel il revient.
     */
    CORBEILLE,
    DELETED
}
