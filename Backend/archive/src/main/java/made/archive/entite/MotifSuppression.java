package made.archive.entite;

/**
 * Pourquoi un document est en corbeille / a été supprimé (voir Document.motifSuppression) — conservé
 * dans la pierre tombale et le journal. Les trois premiers sont choisis par l'éditeur ; FIN_DE_VIE est
 * réservé au système (échéance de conservation atteinte) et ne peut jamais être saisi par un utilisateur.
 */
public enum MotifSuppression
{
    ERREUR_ARCHIVAGE,
    SUPPRESSION_LEGALE,
    /** Exige un commentaire. */
    AUTRE,
    FIN_DE_VIE
}
