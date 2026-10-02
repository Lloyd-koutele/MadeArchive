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

import made.archive.dto.PhysicalLocationArborescenceRequestDto;
import made.archive.dto.PhysicalLocationCreateDto;
import made.archive.dto.PhysicalLocationTreeNodeDto;
import made.archive.dto.PhysicalLocationUpdateDto;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.security.UserDetailsImpl;
import made.archive.service.organisation.PhysicalLocationService;

/**
 * Gestion (écriture) COMPLÈTE de l'arbre de localisation physique PAR
 * L'ÉDITEUR — créer, modifier, déplacer, convertir, désactiver/réactiver,
 * supprimer. Revu le 09/2026 : géré par ADMIN/ADMIN_UO à l'origine, puis
 * seule la création leur avait été retirée dans un premier temps, avant ce
 * passage complet — ADMIN/ADMIN_UO n'ont plus AUCUN droit d'écriture ici,
 * uniquement la lecture (voir PhysicalLocationLectureController). Même
 * modèle que les Dossiers (voir DossierController, sous /api/editor/dossiers).
 *
 * Contrôleur séparé de tout ce qui vit sous /api/admin_uo/**, obligatoire :
 * la règle d'autorisation d'URL de SecurityConfig
 * (.requestMatchers("/api/admin_uo/**").hasRole("ADMIN_UO")) s'évalue AVANT
 * même d'atteindre un @Secured de méthode, et ROLE_EDITOR n'implique pas
 * ROLE_ADMIN_UO dans la hiérarchie (voir SecurityConfig.roleHierarchy) — un
 * @Secured("ROLE_EDITOR") sous /api/admin_uo/** resterait du code mort, un
 * EDITOR étant déjà rejeté avant.
 *
 * Chaque méthode de service (PhysicalLocationService.creer/modifier/deplacer/...)
 * vérifie déjà, en interne, qu'un EDITOR n'agit que dans SA PROPRE UO (voir
 * PhysicalLocationService.estEditeurDeUO) ; ce contrôleur n'ajoute aucune
 * logique d'autorisation propre, seulement le bon rôle/préfixe d'URL.
 *
 * Base : /api/editor/physical-locations
 */
@RestController
@RequestMapping("/api/editor/physical-locations")
public class PhysicalLocationEditorController
{
    private final PhysicalLocationService service;

    public PhysicalLocationEditorController(PhysicalLocationService service)
    {
        this.service = service;
    }

    @Secured("ROLE_EDITOR")
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

    @Secured("ROLE_EDITOR")
    @PostMapping("/arborescence")
    public ResponseEntity<?> creerArborescence(@RequestBody PhysicalLocationArborescenceRequestDto dto,
                                                @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(service.creerArborescence(dto, principal.getUser()));
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

    @Secured("ROLE_EDITOR")
    @PutMapping("/{id}/arborescence")
    public ResponseEntity<?> mettreAJourArborescence(@PathVariable UUID id,
                                                       @RequestBody PhysicalLocationTreeNodeDto dto,
                                                       @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(service.mettreAJourArborescence(id, dto, principal.getUser()));
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
    @PutMapping("/{id}/deplacer")
    public ResponseEntity<?> deplacer(@PathVariable UUID id,
                                       @RequestParam(required = false) UUID nouveauParentId,
                                       @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(service.deplacer(id, nouveauParentId, principal.getUser()));
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

    /** capaciteMax absent/null = retire la limite (illimité) — voir PhysicalLocationService.definirCapacite. */
    @Secured("ROLE_EDITOR")
    @PutMapping("/{id}/capacite")
    public ResponseEntity<?> definirCapacite(@PathVariable UUID id,
                                              @RequestParam(required = false) Integer capaciteMax,
                                              @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(service.definirCapacite(id, capaciteMax, principal.getUser()));
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

    /** Voir PhysicalLocationService.definirContrainte. */
    @Secured("ROLE_EDITOR")
    @PutMapping("/{id}/contrainte")
    public ResponseEntity<?> definirContrainte(@PathVariable UUID id,
                                                @RequestParam String modeContrainte,
                                                @RequestParam(required = false) Long typeDocumentId,
                                                @RequestParam(required = false) Long dossierId,
                                                @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(
                service.definirContrainte(id, modeContrainte, typeDocumentId, dossierId, principal.getUser()));
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

    @Secured("ROLE_EDITOR")
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

    @Secured("ROLE_EDITOR")
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
