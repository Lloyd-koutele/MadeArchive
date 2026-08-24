package made.archive.exception;

public class UONotFoundException extends RuntimeException 
{
    public UONotFoundException(Long id) 
    {
        super("Unité organisationnelle introuvable : id=" + id);
    }
}