package made.archive.entite;

/** Type de l'entité visée par une action journalisée (voir JournalAudit). */
public enum AuditCible
{
    SESSION,
    UTILISATEUR,
    UNITE_ORGANISATIONNELLE,
    DOCUMENT,
    GROUPE_ACCES,
    TYPE_DOCUMENT,
    PROJET,
    PHYSICAL_LOCATION
}
