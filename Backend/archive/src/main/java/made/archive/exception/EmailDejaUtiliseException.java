package made.archive.exception;

public class EmailDejaUtiliseException extends RuntimeException 
{
    public EmailDejaUtiliseException(String email) 
    {
        super("Cet email est déjà utilisé : " + email);
    }
}