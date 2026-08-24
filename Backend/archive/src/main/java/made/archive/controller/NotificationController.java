package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.entite.Notification;
import made.archive.security.UserDetailsImpl;
import made.archive.service.notification.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/notifications")
@RequiredArgsConstructor
public class NotificationController
{
    private final NotificationService notificationService;

    @Secured("ROLE_USER")
    @GetMapping
    public ResponseEntity<?> mesNotifications(@AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        List<Notification> notifications =
            notificationService.getMesNotifications(currentUser.getUser().getId());
        return ResponseEntity.ok(notifications);
    }

    @Secured("ROLE_USER")
    @GetMapping("/non-lues/count")
    public ResponseEntity<?> countNonLues(@AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        long count = notificationService.countNonLues(currentUser.getUser().getId());
        return ResponseEntity.ok(Map.of("count", count));
    }

    @Secured("ROLE_USER")
    @PutMapping("/{id}/lue")
    public ResponseEntity<?> marquerLue(
        @PathVariable Long id,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            notificationService.marquerCommeLue(id, currentUser.getUser());
            return ResponseEntity.ok().build();
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @Secured("ROLE_USER")
    @PutMapping("/lues")
    public ResponseEntity<?> marquerToutesLues(@AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        notificationService.marquerToutesCommeLues(currentUser.getUser());
        return ResponseEntity.ok().build();
    }
}
