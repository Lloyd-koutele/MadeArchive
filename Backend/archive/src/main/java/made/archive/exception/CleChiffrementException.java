package made.archive.exception;

/**
 * La clé de chiffrement au repos fournie à l'application est absente, mal formée ou différente de celle des
 * archives : problème de CONFIGURATION, pas d'altération d'un fichier. Ne doit jamais faire basculer un document
 * en CORROMPU.
 */
public class CleChiffrementException extends BusinessException
{
    private static final long serialVersionUID = 1L;

    public CleChiffrementException(String message)
    {
        super(message);
    }
}
