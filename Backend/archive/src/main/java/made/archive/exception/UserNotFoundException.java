package made.archive.exception;

import java.util.UUID;

public class UserNotFoundException extends RuntimeException
{
    public UserNotFoundException(UUID userId)
    {
        super(" L'utilisateur de l'id " + userId + "n'existe pas");
    }
}
