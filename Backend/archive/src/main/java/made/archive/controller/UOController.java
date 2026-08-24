// UOController.java — migration UserDetailsImpl complétée sur tous les endpoints
package made.archive.controller;

import java.util.UUID;

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
import org.springframework.web.bind.annotation.RestController;

import made.archive.dto.UniteOrganisationnelleDto;
import made.archive.security.UserDetailsImpl;
import made.archive.service.organisation.UniteOrganisationnelleService;

@RestController
@RequestMapping("/api/admin_uo/uo")
public class UOController
{
    private final UniteOrganisationnelleService uoService;

    public UOController(UniteOrganisationnelleService uoService)
    {
        this.uoService = uoService;
    }

    @Secured("ROLE_ADMIN")
    @GetMapping
    public ResponseEntity<?> getAllUOs()
    {
        try
        {
            return ResponseEntity.ok(uoService.getAllUOs());
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors de la récupération des UO: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/me")
    public ResponseEntity<?> getMonUO(@AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(uoService.getMonUO(principal.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors de la récupération de votre UO: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/{id}")
    public ResponseEntity<?> getUOById(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(uoService.getUOById(id, principal.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors de la récupération de l'UO: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/{id}/filles")
    public ResponseEntity<?> getUOsFilles(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(uoService.getUOsFilles(id, principal.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors de la récupération des UO filles: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/{id}/sous-arbre")
    public ResponseEntity<?> getSousArbre(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(uoService.getSousArbre(id, principal.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors de la récupération du sous-arbre: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PostMapping
    public ResponseEntity<?> createUO(@RequestBody UniteOrganisationnelleDto dto, @AuthenticationPrincipal UserDetailsImpl createBy)
    {
        try
        {
            return ResponseEntity.ok(uoService.creatUO(dto, createBy.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors de la création de l'UO: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PutMapping("/{id}")
    public ResponseEntity<?> updateUO(@PathVariable Long id, @RequestBody UniteOrganisationnelleDto dto, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            return ResponseEntity.ok(uoService.updateUO(id, dto, currentUser.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors de la mise à jour de l'UO: " + e.getMessage());
        }
    }

    @Secured("ROLE_ADMIN")
    @PutMapping("/{id}/racine")
    public ResponseEntity<?> deplacerVersRacine(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            return ResponseEntity.ok(uoService.deplacerVersRacine(id, currentUser.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors du déplacement vers la racine: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUO(
            @PathVariable Long id, 
            @AuthenticationPrincipal UserDetailsImpl principal)
    {
        uoService.supprimer(id, principal.getUser().getId());
        return ResponseEntity.noContent().build();
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/{id}/membres")
    public ResponseEntity<?> getMembres(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            return ResponseEntity.ok(uoService.getMembres(id, currentUser.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors de la récupération des membres: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PostMapping("/{id}/membres/{userId}")
    public ResponseEntity<?> ajouterMembre(@PathVariable Long id, @PathVariable UUID userId, @AuthenticationPrincipal UserDetailsImpl demandePar)
    {
        try
        {
            uoService.ajouterMembre(id, userId, demandePar.getUser());
            return ResponseEntity.ok().build();
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors de l'ajout du membre: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @DeleteMapping("/{id}/membres/{userId}")
    public ResponseEntity<?> retirerMembre(@PathVariable Long id, @PathVariable UUID userId, @AuthenticationPrincipal UserDetailsImpl admin)
    {
        try
        {
            uoService.retirerMembre(id, userId, admin.getUser());
            return ResponseEntity.ok().build();
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors du retrait du membre: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @DeleteMapping("/{id}/membres/{userId}/admin")
    public ResponseEntity<?> retirerMembreAndAdmin(@PathVariable Long id, @PathVariable UUID userId, @AuthenticationPrincipal UserDetailsImpl admin)
    {
        try
        {
            uoService.retirerMembreAndAdmin(id, userId, admin.getUser());
            return ResponseEntity.ok().build();
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors du retrait du membre: " + e.getMessage());
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PutMapping("/{id}/membres/{userId}/transferer")
    public ResponseEntity<?> transfererMembre(@PathVariable Long id, @PathVariable UUID userId, @AuthenticationPrincipal UserDetailsImpl demandePar)
    {
        try
        {
            uoService.changerUOUtilisateur(userId, id, demandePar.getUser());
            return ResponseEntity.ok().build();
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body("Erreur lors du transfert: " + e.getMessage());
        }
    }
}