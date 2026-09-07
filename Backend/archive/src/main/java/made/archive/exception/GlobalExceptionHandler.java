package made.archive.exception;

import java.util.Map;

import org.apache.tomcat.util.http.fileupload.impl.FileCountLimitExceededException;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
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
     * server.tomcat.max-part-count/max-parameter-count (nombre de fichiers,
     * pas leur taille) atterrit AUSSI ici, jamais dans handleMultipart
     * ci-dessous. Tomcat lève FileCountLimitExceededException avec pour SEUL
     * message le mot "attachment" — inexploitable — mais
     * StandardMultipartHttpServletRequest (Spring) classe le type d'erreur en
     * cherchant les mots "exceed"/"limit"/"count" dans le texte de la chaîne
     * de causes, qui INCLUT le nom de la classe elle-même :
     * "FileCountLimitExceededException" contient déjà tout ça (Count, Limit,
     * Exceeded) — Spring la reclasse donc à tort en
     * MaxUploadSizeExceededException avant même d'atteindre ce fichier. D'où
     * la même détection explicite du type de la cause ici que dans
     * handleMultipart, plutôt que de faire confiance au type d'exception que
     * Spring a choisi — et par la même occasion, la distinction fichier isolé
     * trop lourd / lot entier trop lourd / trop de fichiers, qu'un seul
     * message générique ne permettait pas.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleTailleDepassee(MaxUploadSizeExceededException ex)
    {
        return reponseSpecifique(ex, "Le ou les fichiers envoyés dépassent la taille autorisée.",
            HttpStatus.CONTENT_TOO_LARGE);
    }

    /**
     * Cas général — en pratique, un dépassement de
     * server.tomcat.max-part-count/max-parameter-count atterrit plutôt dans
     * handleTailleDepassee ci-dessus (voir sa Javadoc) ; ce gestionnaire reste
     * le filet de sécurité pour toute autre erreur de parsing multipart (ex.
     * corps malformé, connexion coupée en cours d'envoi).
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> handleMultipart(MultipartException ex)
    {
        return reponseSpecifique(ex, "Erreur lors de l'envoi des fichiers — vérifiez votre connexion et réessayez.",
            HttpStatus.BAD_REQUEST);
    }

    /**
     * Cherche, dans la chaîne de causes, laquelle des trois limites connues a
     * été dépassée — nombre de fichiers, taille d'UN fichier, ou taille du lot
     * entier — pour un message précis à chaque fois plutôt qu'un seul message
     * générique pour tout dépassement multipart. Retombe sur
     * {@code messageParDefaut} si la cause ne correspond à aucune des trois
     * (ex. corps malformé, connexion coupée en cours d'envoi).
     */
    private ResponseEntity<Map<String, String>> reponseSpecifique(
        Throwable ex, String messageParDefaut, HttpStatus statutParDefaut)
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
            if (cause instanceof FileSizeLimitExceededException fsle)
            {
                return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of("message",
                    "Le fichier \"" + fsle.getFileName() + "\" (" + formatMo(fsle.getActualSize())
                        + ") dépasse la taille maximale autorisée par fichier (" + formatMo(fsle.getPermittedSize())
                        + ")."));
            }
            if (cause instanceof SizeLimitExceededException sle)
            {
                return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of("message",
                    "La taille totale des fichiers envoyés (" + formatMo(sle.getActualSize())
                        + ") dépasse la limite autorisée pour un même lot (" + formatMo(sle.getPermittedSize())
                        + "). Réduisez le nombre ou la taille des fichiers."));
            }
            cause = cause.getCause();
        }
        return ResponseEntity.status(statutParDefaut).body(Map.of("message", messageParDefaut));
    }

    private String formatMo(long octets)
    {
        return DataSize.ofBytes(octets).toMegabytes() + " Mo";
    }
}
