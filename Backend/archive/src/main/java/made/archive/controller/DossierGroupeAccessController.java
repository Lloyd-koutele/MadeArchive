package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.entite.User;
import made.archive.repository.UserRepository;
import made.archive.service.organisation.DossierService;
import made.archive.exception.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Gestion du groupe d'accès d'un dossier PRIVÉ — même schéma que
 * GroupeAccessController (documents) : réservé à un membre du groupe ayant
 * AUSSI le rôle éditeur (voir DossierService.peutGererGroupeDossier), pas au
 * seul créateur — le créateur (dossier.creePar) reste juste protégé, jamais
 * retirable de son propre groupe.
 */
@RestController
@RequestMapping("/api/user/dossiers/{dossierId}/groupe")
@RequiredArgsConstructor
public class DossierGroupeAccessController
{
    private final DossierService dossierService;
    private final UserRepository userRepository;

    private User getDemandeur(UserDetails userDetails)
    {
        return userRepository.findByEmail(userDetails.getUsername())
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));
    }

    /**
     * GET /api/user/dossiers/{dossierId}/groupe/membres
     * Ouvert à tout membre du groupe (pas seulement le créateur).
     */
    @Secured("ROLE_USER")
    @GetMapping("/membres")
    public ResponseEntity<?> getMembres(
        @PathVariable Long dossierId,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            UUID demandeurId = getDemandeur(userDetails).getId();
            return ResponseEntity.ok(dossierService.getMembresDossier(dossierId, demandeurId));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * GET /api/user/dossiers/{dossierId}/groupe/disponibles
     * Réservé à un membre du groupe ayant AUSSI le rôle éditeur.
     */
    @Secured("ROLE_EDITOR")
    @GetMapping("/disponibles")
    public ResponseEntity<?> getDisponibles(
        @PathVariable Long dossierId,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            UUID demandeurId = getDemandeur(userDetails).getId();
            List<User> disponibles = dossierService.getUtilisateursDisponiblesDossier(dossierId, demandeurId);
            return ResponseEntity.ok(disponibles);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * POST /api/user/dossiers/{dossierId}/groupe/membres?nouveauMembreId=
     * Réservé à un membre du groupe ayant AUSSI le rôle éditeur.
     */
    @Secured("ROLE_EDITOR")
    @PostMapping("/membres")
    public ResponseEntity<?> ajouterMembre(
        @PathVariable Long dossierId,
        @RequestParam UUID nouveauMembreId,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            UUID demandeurId = getDemandeur(userDetails).getId();
            dossierService.ajouterMembreDossier(dossierId, demandeurId, nouveauMembreId);
            return ResponseEntity.ok("Membre ajouté avec succès");
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * DELETE /api/user/dossiers/{dossierId}/groupe/membres/{membreId}
     * Réservé à un membre du groupe ayant AUSSI le rôle éditeur — le créateur
     * du dossier ne peut jamais être retiré, lui.
     */
    @Secured("ROLE_EDITOR")
    @DeleteMapping("/membres/{membreId}")
    public ResponseEntity<?> retirerMembre(
        @PathVariable Long dossierId,
        @PathVariable UUID membreId,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            UUID demandeurId = getDemandeur(userDetails).getId();
            dossierService.retirerMembreDossier(dossierId, demandeurId, membreId);
            return ResponseEntity.ok("Membre retiré avec succès");
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
