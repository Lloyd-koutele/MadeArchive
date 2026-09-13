package made.archive.entite;

public enum NotificationType
{
    /** Document détecté corrompu lors d'une vérification de routine (fixity check). */
    DOCUMENT_CORROMPU,

    /** Nouveau document ajouté dans une UO (ou dans un groupe d'accès si privé). */
    DOCUMENT_AJOUTE,

    /** Nouveau projet créé dans une UO. */
    PROJET_CREE,

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
    GROUPE_MEMBRE_RETIRE
}
