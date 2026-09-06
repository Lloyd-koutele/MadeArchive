package made.archive.exception;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sans ce gestionnaire, une violation de contrainte Bean Validation (@Valid,
 * ex. UserDto.telephone) déclenche un MethodArgumentNotValidException AVANT
 * même d'entrer dans le corps du contrôleur (résolution des arguments) — le
 * try/catch de la méthode ne le voit donc jamais. Spring Boot redirige alors
 * en interne vers /error, un forward SANS l'en-tête Authorization d'origine ;
 * le filtre JWT n'a rien à authentifier sur cette requête interne, et le
 * client reçoit un 401 "Authentication required" trompeur au lieu du 400
 * attendu — constaté en conditions réelles en testant la validation du
 * téléphone sur createUser. Ce gestionnaire intercepte l'exception avant ce
 * forward et renvoie directement un 400 avec le message français du champ en
 * cause, dans la même forme JSON ({"message": "..."}) que le reste de l'API.
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex)
    {
        FieldError premiereErreur = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = premiereErreur != null
            ? premiereErreur.getDefaultMessage()
            : "Données invalides";
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
