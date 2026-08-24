package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.entite.User;
import made.archive.repository.UserRepository;
import made.archive.service.document.GroupeAccessService;
import made.archive.exception.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/user/documents/{documentId}/groupe")
@RequiredArgsConstructor
public class GroupeAccessController
{
    private final GroupeAccessService groupeAccessService;
    private final UserRepository userRepository;

    /**
     * Extrait l'utilisateur connecté depuis le token JWT.
     */
    private User getDemandeur(UserDetails userDetails)
    {
        return userRepository.findByEmail(userDetails.getUsername())
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));
    }

    /**
     * GET /api/user/documents/{documentId}/groupe/membres
     * Liste les membres ayant accès au document.
     */
    @Secured("ROLE_USER")
    @GetMapping("/membres")
    public ResponseEntity<?> getMembres(
        @PathVariable UUID documentId,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            UUID demandeurId = getDemandeur(userDetails).getId();
            return ResponseEntity.ok(groupeAccessService.getMembres(documentId, demandeurId));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * GET /api/user/documents/{documentId}/groupe/disponibles
     * Liste les utilisateurs disponibles à ajouter au groupe.
     */
    @Secured({"ROLE_EDITOR", "ROLE_ADMIN"})
    @GetMapping("/disponibles")
    public ResponseEntity<?> getDisponibles(
        @PathVariable UUID documentId,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            UUID demandeurId = getDemandeur(userDetails).getId();
            List<User> disponibles = groupeAccessService
                .getUtilisateursDisponibles(documentId, demandeurId);
            return ResponseEntity.ok(disponibles);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * POST /api/user/documents/{documentId}/groupe/membres?nouveauMembreId=
     * Ajoute un membre au groupe.
     */
    @Secured({"ROLE_EDITOR", "ROLE_ADMIN"})
    @PostMapping("/membres")
    public ResponseEntity<?> ajouterMembre(
        @PathVariable UUID documentId,
        @RequestParam UUID nouveauMembreId,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            UUID demandeurId = getDemandeur(userDetails).getId();
            groupeAccessService.ajouterMembre(documentId, demandeurId, nouveauMembreId);
            return ResponseEntity.ok("Membre ajouté avec succès");
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * DELETE /api/user/documents/{documentId}/groupe/membres/{membreId}
     * Retire un membre du groupe.
     * Si groupe vide → document devient PUBLIC.
     */
    @Secured({"ROLE_EDITOR", "ROLE_ADMIN"})
    @DeleteMapping("/membres/{membreId}")
    public ResponseEntity<?> retirerMembre(
        @PathVariable UUID documentId,
        @PathVariable UUID membreId,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            UUID demandeurId = getDemandeur(userDetails).getId();
            groupeAccessService.retirerMembre(documentId, demandeurId, membreId);
            return ResponseEntity.ok("Membre retiré avec succès");
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}