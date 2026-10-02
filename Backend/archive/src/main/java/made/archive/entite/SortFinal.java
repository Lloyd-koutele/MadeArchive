package made.archive.entite;

/**
 * Décision de sort final d'une règle de rétention — gouverne ce qui se passe
 * une fois le délai de grâce de la corbeille écoulé (voir
 * DocumentRetentionService.purgeDocumentsCorbeille et DocumentService.DELAI_GRACE_CORBEILLE_JOURS),
 * pour un document arrivé en CORBEILLE par n'importe quelle voie (suppression
 * volontaire par un éditeur OU fin de rétention légale — les deux partagent le
 * même délai de grâce et la même décision de sort final, voir Retention.sortFinal).
 */
public enum SortFinal
{
    /** Le document n'est JAMAIS purgé automatiquement, même après le délai de
     *  grâce — il reste en CORBEILLE, consultable/restaurable comme avant,
     *  jusqu'à une suppression définitive manuelle par un éditeur
     *  (voir DocumentService.supprimerDefinitivementDepuisCorbeille). */
    CONSERVER,

    /** Purge automatique dès le délai de grâce écoulé — comportement historique
     *  (seul comportement qui existait avant l'introduction du sort final). */
    DETRUIRE,

    /** Même comportement que CONSERVER après le délai de grâce (pas de purge
     *  automatique, suppression manuelle possible) — distinct de CONSERVER dans
     *  l'intention affichée à l'éditeur (un tri reste à faire), identique dans
     *  les faits tant qu'aucun outil de tri dédié n'existe. */
    TRIER
}
