package made.archive.service.importweb;

import made.archive.exception.BusinessException;

/** Le serveur distant a répondu par un code HTTP d'erreur (hors redirection) — le code permet un message précis. */
public class ErreurHttpException extends BusinessException
{
    private static final long serialVersionUID = 1L;

    private final int statut;

    public ErreurHttpException(int statut)
    {
        super("Le serveur distant a répondu avec le code " + statut);
        this.statut = statut;
    }

    public int statut()
    {
        return statut;
    }
}
