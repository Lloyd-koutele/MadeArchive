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
    DOCUMENT_INCLUS_DANS_EXPORT
}
