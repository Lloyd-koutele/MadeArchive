// AdminController.java
package made.archive.controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import made.archive.dto.UserDto;
import made.archive.dto.UserResponseDto;
import made.archive.entite.User;
import made.archive.security.UserDetailsImpl;
import made.archive.service.user.UserService;


@RestController
@RequestMapping("/api/admin_uo")
public class AdminController
{
    private final UserService userService;

    public AdminController(UserService userService)
    {
        this.userService = userService;
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PostMapping("/users/create-user")
    public ResponseEntity<?> createUser(@RequestBody UserDto dto, @RequestParam(required = false) List<Long> uoIds, @AuthenticationPrincipal UserDetailsImpl createPar)
    {
        try
        {
            return ResponseEntity.ok(userService.createUser(dto, uoIds, createPar.getUser()));
        }
        catch (Exception e)
        {
            return errorResponse("Erreur lors de la création de l'utilisateur: " + e.getMessage());
        }
    }

    // Vue globale non scopée : réservée à ADMIN. Un ADMIN_UO utilise /users/uo/{uoId}
    @Secured("ROLE_ADMIN")
    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers(@AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            List<UserResponseDto> users = userService.getAllUsers(currentUser.getUser());
            return ResponseEntity.ok(users);
        }
        catch (Exception e)
        {
            return errorResponse("Erreur lors de la récupération des administrateurs: " + e.getMessage());
        }
    }

    // Navigation ADMIN_UO : utilisateurs membres d'une UO précise
    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/users/uo/{uoId}")
    public ResponseEntity<?> getUsersByUO(@PathVariable Long uoId, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            return ResponseEntity.ok(userService.getUsersByUO(uoId, currentUser.getUser()));
        }
        catch (Exception e)
        {
            return errorResponse("Erreur lors de la récupération des utilisateurs de l'UO: " + e.getMessage());
        }
    }

    @Secured("ROLE_ADMIN")
    @GetMapping("/users/inactifs")
    public ResponseEntity<?> getUsersInactifs()
    {
        try
        {
            List<User> users = userService.getUsersByStatus(false);
            return ResponseEntity.ok(users);
        }
        catch (Exception e)
        {
            return errorResponse("Erreur lors de la récupération des utilisateurs inactifs: " + e.getMessage());
        }
    }

    @Secured("ROLE_ADMIN")
    @GetMapping("/users/actifs")
    public ResponseEntity<?> getUsersActifs()
    {
        try
        {
            List<User> users = userService.getUsersByStatus(true);
            return ResponseEntity.ok(users);
        }
        catch (Exception e)
        {
            return errorResponse("Erreur lors de la récupération des utilisateurs actifs: " + e.getMessage());
        }
    }

    @Secured("ROLE_ADMIN")
    @GetMapping("/users/{userId}/status")
    public ResponseEntity<?> checkUserStatus(@PathVariable UUID userId)
    {
        try
        {
            Boolean isActive = userService.isUserActive(userId);
            return ResponseEntity.ok(isActive);
        }
        catch (Exception e)
        {
            return errorResponse("Erreur lors de la vérification du statut: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PutMapping("/users/status/{id}")
    public ResponseEntity<?> updateUserStatus(@PathVariable UUID id, @Valid @RequestBody UserDto dto, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            User updatedUser = userService.updateUserStatus(id, dto, currentUser.getUser());
            return ResponseEntity.ok(updatedUser);
        }
        catch (Exception e)
        {
            return errorResponse("Erreur lors de la mise à jour du statut: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PutMapping("/users/update-user/{id}")
    public ResponseEntity<?> updateUser(@PathVariable UUID id, @Valid @RequestBody UserDto dto, @RequestParam(required = false) Long uoId, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            Optional<UserDto> updatedUser = userService.updateUser(id, dto, uoId, currentUser.getUser());
            return ResponseEntity.ok(updatedUser);
        }
        catch (Exception e)
        {
            return errorResponse("Erreur lors de la mise à jour de l'utilisateur: " + e.getMessage());
        }
    }

    // getUser(id) a été retiré d'ici : @Secured("ROLE_USER") sous /api/admin_uo/** était
    // inatteignable pour un simple USER (la règle d'URL exige ROLE_ADMIN_UO avant même
    // d'atteindre cette annotation — même défaut qu'on a corrigé pour les projets et l'UO
    // courante). Equivalent déjà fonctionnel et correctement exposé : UserController
    // GET /api/user/me/{id} (accepte un ID arbitraire malgré son nom — pas seulement "soi-même").

    /**
     * Réponse d'erreur en JSON ({"message": "..."}) — pas une String brute.
     * Le frontend lit systématiquement error.response.data.message ; une
     * String brute rendait ce message inaccessible côté client.
     */
    private ResponseEntity<?> errorResponse(String message)
    {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}