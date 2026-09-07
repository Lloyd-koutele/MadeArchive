package made.archive.exception;

import java.util.Map;

import org.apache.tomcat.util.http.fileupload.impl.FileCountLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

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
    @Value("${server.tomcat.max-part-count}")
    private long maxPartCount;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex)
    {
        FieldError premiereErreur = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = premiereErreur != null
            ? premiereErreur.getDefaultMessage()
            : "Données invalides";
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    /**
     * Dépassement de spring.servlet.multipart.max-file-size/max-request-size
     * (taille d'un fichier ou du lot entier) — MaxUploadSizeExceededException
     * hérite de MultipartException (voir le gestionnaire ci-dessous, qui NE
     * doit PAS traiter ce cas : Spring choisit toujours le gestionnaire le
     * plus spécifique, donc celui-ci prend la main en premier).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleTailleDepassee(MaxUploadSizeExceededException ex)
    {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
            .body(Map.of("message", "Le ou les fichiers envoyés dépassent la taille autorisée."));
    }

    /**
     * Cas général — englobe notamment le dépassement de
     * server.tomcat.max-part-count (nombre de fichiers dans le lot, ex.
     * "choisir un dossier entier" à l'archivage). Le message que Tomcat met
     * dans FileCountLimitExceededException est inexploitable tel quel
     * ("attachment", constaté en conditions réelles — pas de mention
     * "exceeded"/"limit"/"count") : impossible de le distinguer d'une autre
     * erreur multipart par le texte, d'où la détection explicite du type de
     * la cause plutôt qu'un mot-clé dans le message.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> handleMultipart(MultipartException ex)
    {
        Throwable cause = ex;
        while (cause != null)
        {
            if (cause instanceof FileCountLimitExceededException)
            {
                return ResponseEntity.badRequest().body(Map.of("message",
                    "Trop de fichiers envoyés en une seule fois (maximum " + maxPartCount
                        + "). Réduisez la taille du lot et réessayez."));
            }
            cause = cause.getCause();
        }
        return ResponseEntity.badRequest().body(Map.of("message",
            "Erreur lors de l'envoi des fichiers — vérifiez votre connexion et réessayez."));
    }
}
