package made.archive.entite;

public enum NotificationType
{
    /** Document détecté corrompu lors d'une vérification de routine (fixity check). */
    DOCUMENT_CORROMPU,

    /** Nouveau document ajouté dans une UO (ou dans un groupe d'accès si privé). */
    DOCUMENT_AJOUTE,

    /** Nouveau dossier créé dans une UO. */
    DOSSIER_CREE,

    /** Nouvelle unité organisationnelle créée (racine ou enfant). */
    UO_CREEE,

    /** Horodatage RFC 3161 échoué à l'upload (TSA injoignable...) — repris automatiquement en tâche de fond. */
    DOCUMENT_HORODATAGE_ECHEC,

    /** Horodatage RFC 3161 finalement obtenu, après un échec initial. */
    DOCUMENT_HORODATAGE_REUSSI,

    /** Export administratif demandé par cet utilisateur prêt à télécharger. */
    EXPORT_PRET,

    /** Notification OBLIGATOIRE (jamais optionnelle) : au moins un de vos documents
     *  privés — ou un document privé d'un groupe dont vous êtes admin_uo — a été
     *  inclus dans un export par un ADMIN qui n'en est pas membre. Voir
     *  DocumentExportService — transparence délibérée, pas un simple journal
     *  d'audit que personne ne consulte. */
    DOCUMENT_INCLUS_DANS_EXPORT,

    /** Vérification d'intégrité déclenchée manuellement (ADMIN/ADMIN_UO) —
     *  envoyée au DEMANDEUR une fois le contrôle terminé (succès ou échec),
     *  distincte de DOCUMENT_CORROMPU qui va, elle, aux ayants-droit de
     *  chaque document trouvé corrompu. Voir FixityCheckTriggerService. */
    FIXITY_CHECK_TERMINE,

    /** Vous avez été ajouté au groupe d'accès d'un document privé — envoyée
     *  au NOUVEAU membre lui-même, pas aux autres membres déjà présents.
     *  Voir GroupeAccessService.ajouterMembre. */
    GROUPE_MEMBRE_AJOUTE,

    /** Vous avez été retiré du groupe d'accès d'un document privé — envoyée
     *  au membre RETIRÉ lui-même (il perd l'accès au document à partir de ce
     *  moment, il doit en être informé). Voir GroupeAccessService.retirerMembre. */
    GROUPE_MEMBRE_RETIRE,

    /** Le LLM configuré pour la génération automatique de regex (Ollama local
     *  et/ou API externe, voir OllamaService) est injoignable ou a refusé la
     *  connexion — envoyée à tous les ADMIN globaux, au plus une fois par
     *  cooldown (voir RedisCacheConfig.CACHE_LLM_INVALIDE_COOLDOWN) pour ne
     *  pas spammer même si plusieurs types de documents échouent en même
     *  temps. Purement informatif : la génération automatique est
     *  best-effort, aucun document n'est jamais bloqué par cette panne. */
    LLM_GENERATION_INDISPONIBLE,

    /** Le TSA configuré pour l'horodatage RFC 3161 (gratuit et/ou payant, voir
     *  HorodatageService/HorodatageProperties) est injoignable, a refusé la
     *  connexion, ou a rejeté la requête — envoyée à tous les ADMIN globaux,
     *  au plus une fois par cooldown (voir RedisCacheConfig
     *  .CACHE_HORODATAGE_INVALIDE_COOLDOWN) pour ne pas spammer même si
     *  plusieurs documents échouent dans la même fenêtre. Couvre notamment un
     *  abonnement payant coupé (impayé, quota dépassé...) — distincte de
     *  DOCUMENT_HORODATAGE_ECHEC (par document, envoyée à l'éditeur) : celle-ci
     *  signale LE LIEN lui-même, pas un document précis. Purement informatif :
     *  l'horodatage est best-effort, aucun document n'est jamais bloqué par
     *  cette panne. */
    HORODATAGE_INDISPONIBLE,

    /** Documents en corbeille que le système va supprimer dans moins de 3 jours (une alerte par jour). */
    DOCUMENT_SUPPRESSION_IMMINENTE
}
