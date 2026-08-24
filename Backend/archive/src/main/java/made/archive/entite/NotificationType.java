package made.archive.entite;

public enum NotificationType
{
    /** Document détecté corrompu lors d'une vérification de routine (fixity check). */
    DOCUMENT_CORROMPU,

    /** Nouveau document ajouté dans une UO (ou dans un groupe d'accès si privé). */
    DOCUMENT_AJOUTE,

    /** Nouveau projet créé dans une UO. */
    PROJET_CREE
}
