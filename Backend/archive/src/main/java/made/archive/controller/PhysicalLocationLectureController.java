package made.archive.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.security.UserDetailsImpl;
import made.archive.service.organisation.PhysicalLocationService;

/**
 * Lecture de l'arbre de localisation physique — réservée à ROLE_EDITOR
 * (revu le 09/2026 : ADMIN/ADMIN_UO n'ont plus AUCUN droit, même de lecture,
 * sur les emplacements physiques — seule leur propre UO leur donnait un
 * intérêt à consulter cet arbre, et ils n'en gèrent plus le contenu depuis
 * que la gestion complète est passée à l'éditeur, voir
 * PhysicalLocationEditorController). Utilisée exclusivement par l'éditeur
 * pour parcourir l'arbre et choisir un emplacement au moment de l'upload —
 * aucun autre rôle n'en a besoin, ni ADMIN/ADMIN_UO ni un simple USER.
 *
 * Base : /api/user/physical-locations (préfixe hérité, laissé tel quel :
 * le middleware d'URL de SecurityConfig exige déjà ROLE_USER dessus, et
 * ROLE_EDITOR l'implique via la hiérarchie — voir SecurityConfig.roleHierarchy
 * — donc aucun conflit avec le @Secured plus restrictif ci-dessous).
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

    @Secured("ROLE_EDITOR")
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

    @Secured("ROLE_EDITOR")
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

    /**
     * typeDocumentId/dossierId (optionnels) filtrent par compatibilité — voir
     * PhysicalLocationService.getEmplacementsDisponibles. Omis = comportement
     * historique (tous les points de stockage actifs, sans filtrage).
     */
    @Secured("ROLE_EDITOR")
    @GetMapping("/uo/{uoId}/disponibles")
    public ResponseEntity<?> getEmplacementsDisponibles(@PathVariable Long uoId,
                                                          @RequestParam(required = false) Long typeDocumentId,
                                                          @RequestParam(required = false) Long dossierId,
                                                          @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(
                service.getEmplacementsDisponibles(uoId, typeDocumentId, dossierId, principal.getUser()));
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
