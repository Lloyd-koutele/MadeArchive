package made.archive.controller;

import made.archive.dto.UserDto;
import made.archive.dto.UserResponseDto;
import made.archive.exception.BusinessException;
import made.archive.service.user.UserService;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.access.annotation.Secured;
import org.springframework.http.ResponseEntity;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/user")
public class UserController 
{
    private final UserService userService;

    public UserController(UserService userService)
    {
        this.userService = userService;
    }

    @Secured("ROLE_USER")
    @GetMapping("/me/{id}")
    public ResponseEntity<UserResponseDto> getMyProfile(@PathVariable UUID id) 
    {
       try
       {
        return userService.getMe(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new BusinessException("Aucun profil utilisateur trouvé pour cet ID"));
       }
       catch(Exception e)
       {
            throw new BusinessException("Erreur lors de la récupération du profil utilisateur: " + e.getMessage());
       }
    }

    /**
     * Heartbeat léger interrogé périodiquement par le client (voir SessionGuard côté
     * frontend) pour détecter, même sans action de l'utilisateur, un blocage de compte
     * ou une invalidation de session (changement de rôle / mot de passe). Le filtre JWT
     * renvoie déjà un 401{"reason":...} avant d'atteindre ce contrôleur dans ces cas ;
     * s'il répond 200, la session est toujours valide.
     */
    @Secured("ROLE_USER")
    @GetMapping("/session/check")
    public ResponseEntity<Void> checkSession()
    {
        return ResponseEntity.ok().build();
    }

    @Secured("ROLE_USER")
    @PutMapping("/update-me/{id}")
    public ResponseEntity<UserDto> updateMyProfile(@PathVariable UUID id, @RequestBody UserDto dto)
    {
        try
        {
            return userService.updateMe(id, dto)
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new BusinessException("Aucun profil utilisateur trouvé pour cet ID"));
        }
        catch(Exception e)
        {
            throw new BusinessException("Erreur lors de la mise à jour du profil utilisateur: " + e.getMessage());
        }
    }

}
