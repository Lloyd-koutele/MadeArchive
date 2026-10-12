package made.archive.service.storage;

/**
 * Le fichier qui vient d'être déposé dans le stockage n'y est pas arrivé tel qu'il a été envoyé (absent, taille
 * différente ou contenu différent). Levée AVANT toute écriture en base : l'appelant retire l'objet et abandonne
 * l'archivage, donc aucune ligne ne peut pointer vers un fichier manquant ou altéré.
 */
public class DepotStockageException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public DepotStockageException(String message)
    {
        super(message);
    }

    public DepotStockageException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
