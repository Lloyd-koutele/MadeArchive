package made.archive.entite;

public enum CheckResult 
{
    OK,
    CORRUPTED,
    EMPTY,

    /** Le fichier est conforme à une preuve indépendante de la base (signature HSM, jeton RFC 3161,
     *  manifeste verrouillé), mais l'enregistrement en base ne l'est plus : les PREUVES ont été
     *  modifiées, pas le document. Le document n'est pas marqué corrompu. */
    PREUVE_ALTEREE
}
