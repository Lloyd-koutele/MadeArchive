package made.archive.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import made.archive.dto.PlanClassementNoeudRequestDto;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.security.UserDetailsImpl;
import made.archive.service.organisation.PlanClassementService;

/**
 * Plan de classement de l'UO de l'éditeur — lecture ET écriture réservées à
 * ROLE_EDITOR (sous /api/editor, pas /api/admin_uo : cette seconde règle d'URL
 * exige ROLE_ADMIN_UO avant même d'atteindre @Secured, voir
 * PhysicalLocationEditorController). L'autorité réelle (éditeur de CETTE UO)
 * est vérifiée dans PlanClassementService.
 */
@RestController
@RequestMapping("/api/editor/plan-classement")
@RequiredArgsConstructor
public class PlanClassementController
{
    private final PlanClassementService service;

    @Secured("ROLE_EDITOR")
    @GetMapping("/uo/{uoId}")
    public ResponseEntity<?> getArbre(@PathVariable Long uoId, @AuthenticationPrincipal UserDetailsImpl principal)
    {
        return executer(() -> service.getArbre(uoId, principal.getUser()));
    }

    @Secured("ROLE_EDITOR")
    @PostMapping
    public ResponseEntity<?> creer(@RequestBody PlanClassementNoeudRequestDto dto,
                                   @AuthenticationPrincipal UserDetailsImpl principal)
    {
        return executer(() -> service.creer(dto, principal.getUser()));
    }

    @Secured("ROLE_EDITOR")
    @PutMapping("/{id}")
    public ResponseEntity<?> modifier(@PathVariable Long id, @RequestBody PlanClassementNoeudRequestDto dto,
                                      @AuthenticationPrincipal UserDetailsImpl principal)
    {
        return executer(() -> service.modifier(id, dto, principal.getUser()));
    }

    /** parentId absent = déplacer à la racine. */
    @Secured("ROLE_EDITOR")
    @PutMapping("/{id}/deplacer")
    public ResponseEntity<?> deplacer(@PathVariable Long id, @RequestParam(required = false) Long parentId,
                                      @AuthenticationPrincipal UserDetailsImpl principal)
    {
        return executer(() -> service.deplacer(id, parentId, principal.getUser()));
    }

    @Secured("ROLE_EDITOR")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> supprimer(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl principal)
    {
        return executer(() -> { service.supprimer(id, principal.getUser()); return java.util.Map.of("success", true); });
    }

    /** noeudId absent = détacher le type de toute activité. Modifiable même si le type a des documents. */
    @Secured("ROLE_EDITOR")
    @PutMapping("/types/{typeId}")
    public ResponseEntity<?> rattacherType(@PathVariable Long typeId, @RequestParam(required = false) Long noeudId,
                                           @AuthenticationPrincipal UserDetailsImpl principal)
    {
        return executer(() -> {
            service.rattacherType(typeId, noeudId, principal.getUser());
            return java.util.Map.of("success", true);
        });
    }

    private ResponseEntity<?> executer(java.util.function.Supplier<Object> action)
    {
        try
        {
            return ResponseEntity.ok(action.get());
        }
        catch (AccessDeniedException e)
        {
            return ResponseEntity.status(403).body(java.util.Map.of("message", e.getMessage()));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }
}
