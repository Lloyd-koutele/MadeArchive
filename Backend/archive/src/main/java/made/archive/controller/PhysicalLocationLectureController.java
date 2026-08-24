package made.archive.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.security.UserDetailsImpl;
import made.archive.service.organisation.PhysicalLocationService;

/**
 * Lecture de l'arbre de localisation physique — ouvert à tout ROLE_USER,
 * mais scopé exactement comme la visibilité des documents/projets (voir
 * UniteOrganisationnelleService.getUoIdsVisiblesPourLecture) : plus large
 * que la gestion (ADMIN/ADMIN_UO only), pour permettre à un éditeur de
 * parcourir l'arbre et choisir un emplacement au moment de l'upload.
 *
 * Base : /api/user/physical-locations
 */
@RestController
@RequestMapping("/api/user/physical-locations")
public class PhysicalLocationLectureController
{
    private final PhysicalLocationService service;

    public PhysicalLocationLectureController(PhysicalLocationService service)
    {
        this.service = service;
    }

    @Secured("ROLE_USER")
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable UUID id,
                                      @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(service.getById(id, principal.getUser()));
        }
        catch (AccessDeniedException e)
        {
            return ResponseEntity.status(403).body(e.getMessage());
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Secured("ROLE_USER")
    @GetMapping("/uo/{uoId}/arbre")
    public ResponseEntity<?> getArbre(@PathVariable Long uoId,
                                       @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(service.getArbre(uoId, principal.getUser()));
        }
        catch (AccessDeniedException e)
        {
            return ResponseEntity.status(403).body(e.getMessage());
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Secured("ROLE_USER")
    @GetMapping("/uo/{uoId}/disponibles")
    public ResponseEntity<?> getEmplacementsDisponibles(@PathVariable Long uoId,
                                                          @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(service.getEmplacementsDisponibles(uoId, principal.getUser()));
        }
        catch (AccessDeniedException e)
        {
            return ResponseEntity.status(403).body(e.getMessage());
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
