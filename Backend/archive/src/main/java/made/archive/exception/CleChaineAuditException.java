package made.archive.exception;

/**
 * La clé HMAC qui authentifie la chaîne du journal d'audit est absente du HSM ou n'est pas celle de la chaîne :
 * problème de CONFIGURATION, pas une falsification. Le chaînage s'arrête (jamais de repli sur un algorithme plus
 * faible), les entrées restent en attente et seront chaînées dès que la clé est rétablie.
 */
public class CleChaineAuditException extends BusinessException
{
    private static final long serialVersionUID = 1L;

    public CleChaineAuditException(String message)
    {
        super(message);
    }
}
