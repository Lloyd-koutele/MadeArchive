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
     * traite ce cas QUE si ce n'est pas lui : Spring choisit toujours le
     * gestionnaire le plus spécifique, donc celui-ci prend la main en premier.
     *
     * PIÈGE constaté en conditions réelles : un dépassement de
     * server.tomcat.max-part-count (nombre de fichiers, pas leur taille)
     * atterrit AUSSI ici, jamais dans handleMultipart ci-dessous. Tomcat lève
     * FileCountLimitExceededException avec pour SEUL message le mot
     * "attachment" — inexploitable — mais StandardMultipartHttpServletRequest
     * (Spring) classe le type d'erreur en cherchant les mots "exceed"/"limit"/
     * "count" dans le texte de la chaîne de causes, qui INCLUT le nom de la
     * classe elle-même : "FileCountLimitExceededException" contient déjà tout
     * ça (Count, Limit, Exceeded) — Spring la reclasse donc à tort en
     * MaxUploadSizeExceededException avant même d'atteindre ce fichier. D'où
     * la même détection explicite de la cause ici que dans handleMultipart,
     * plutôt que de faire confiance au type d'exception que Spring a choisi.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleTailleDepassee(MaxUploadSizeExceededException ex)
    {
        String messageNombreFichiers = messageSiTropDeFichiers(ex);
        if (messageNombreFichiers != null)
        {
            return ResponseEntity.badRequest().body(Map.of("message", messageNombreFichiers));
        }
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
            .body(Map.of("message", "Le ou les fichiers envoyés dépassent la taille autorisée."));
    }

    /**
     * Cas général — en pratique, un dépassement de server.tomcat.max-part-count
     * atterrit plutôt dans handleTailleDepassee ci-dessus (voir sa Javadoc) ;
     * ce gestionnaire reste le filet de sécurité pour toute autre erreur de
     * parsing multipart (ex. corps malformé, connexion coupée en cours d'envoi).
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> handleMultipart(MultipartException ex)
    {
        String messageNombreFichiers = messageSiTropDeFichiers(ex);
        if (messageNombreFichiers != null)
        {
            return ResponseEntity.badRequest().body(Map.of("message", messageNombreFichiers));
        }
        return ResponseEntity.badRequest().body(Map.of("message",
            "Erreur lors de l'envoi des fichiers — vérifiez votre connexion et réessayez."));
    }

    /**
     * Cherche un FileCountLimitExceededException dans la chaîne de causes —
     * seul moyen fiable de reconnaître ce cas précis, son message texte
     * ("attachment") étant inexploitable. Retourne le message dédié si trouvé,
     * null sinon (laisse alors l'appelant retomber sur son message générique).
     */
    private String messageSiTropDeFichiers(Throwable ex)
    {
        Throwable cause = ex;
        while (cause != null)
        {
            if (cause instanceof FileCountLimitExceededException)
            {
                return "Trop de fichiers envoyés en une seule fois (maximum " + maxPartCount
                    + "). Réduisez la taille du lot et réessayez.";
            }
            cause = cause.getCause();
        }
        return null;
    }
}
