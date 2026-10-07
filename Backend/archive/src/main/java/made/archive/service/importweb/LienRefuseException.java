package made.archive.service.importweb;

import made.archive.exception.BusinessException;

/**
 * Un lien d'import a été refusé pour une raison de SÉCURITÉ (adresse interne ou privée, schéma interdit) — par opposition
 * à une simple panne réseau. Distincte pour pouvoir être tracée dans le journal d'audit.
 */
public class LienRefuseException extends BusinessException
{
    public LienRefuseException(String message)
    {
        super(message);
    }
}
