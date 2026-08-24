package made.archive.exception;

public class UONomDejeExistantException extends RuntimeException
{
    public UONomDejeExistantException(String nom)
    {
        super("Une unite organisationnelle du nom " + nom + " existe deja");
    }
    
}
