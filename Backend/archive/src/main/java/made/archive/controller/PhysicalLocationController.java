package made.archive.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import made.archive.dto.PhysicalLocationCreateDto;
import made.archive.dto.PhysicalLocationUpdateDto;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.security.UserDetailsImpl;
import made.archive.service.organisation.PhysicalLocationService;

/**
 * Gestion (écriture) de l'arbre de localisation physique — réservée à ADMIN
 * (partout) et ADMIN_UO (seulement sur leur UO et ses UO descendantes, voir
 * PhysicalLocationService/UniteOrganisationnelleService.aAutoriteSur).
 *
 * Base : /api/admin_uo/physical-locations
 */
@RestController
@RequestMapping("/api/admin_uo/physical-locations")
public class PhysicalLocationController
{
    private final PhysicalLocationService service;

    public PhysicalLocationController(PhysicalLocationService service)
    {
        this.service = service;
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PostMapping
    public ResponseEntity<?> creer(@RequestBody PhysicalLocationCreateDto dto,
                                    @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(service.creer(dto, principal.getUser()));
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

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PutMapping("/{id}")
    public ResponseEntity<?> modifier(@PathVariable UUID id,
                                       @RequestBody PhysicalLocationUpdateDto dto,
                                       @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(service.modifier(id, dto, principal.getUser()));
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

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PutMapping("/{id}/type-stockage")
    public ResponseEntity<?> changerTypeStockage(@PathVariable UUID id,
                                                  @RequestParam boolean storagePoint,
                                                  @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(service.changerTypeStockage(id, storagePoint, principal.getUser()));
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

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PutMapping("/{id}/desactiver")
    public ResponseEntity<?> desactiver(@PathVariable UUID id,
                                         @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(service.desactiver(id, principal.getUser()));
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

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PutMapping("/{id}/reactiver")
    public ResponseEntity<?> reactiver(@PathVariable UUID id,
                                        @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(service.reactiver(id, principal.getUser()));
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

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @DeleteMapping("/{id}")
    public ResponseEntity<?> supprimer(@PathVariable UUID id,
                                        @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            service.supprimer(id, principal.getUser());
            return ResponseEntity.noContent().build();
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
