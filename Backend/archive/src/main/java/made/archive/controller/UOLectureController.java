package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.security.UserDetailsImpl;
import made.archive.service.organisation.UniteOrganisationnelleService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lecture seule de l'UO courante de l'appelant — séparé de UOController
 * (gestion, sous /api/admin_uo) pour la même raison que ProjetLectureController :
 * /api/admin_uo/** est gatée par ROLE_ADMIN_UO au niveau des URLs, donc EDITOR
 * et USER n'y accèdent jamais, même via @Secured. Or EDITOR et USER ont eux
 * aussi une UO unique de rattachement (voir UniteOrganisationnelleService —
 * la règle "une UO active par utilisateur" s'applique à tous les rôles sauf
 * ADMIN) et ont besoin de la connaître (titre de sidebar, scope du panneau
 * Projets...). /api/user/** ne demande que ROLE_USER, hérité par EDITOR,
 * ADMIN_UO et ADMIN dans la hiérarchie de rôles (SecurityConfig.roleHierarchy).
 */
@RestController
@RequestMapping("/api/user/uo")
@RequiredArgsConstructor
public class UOLectureController
{
    private final UniteOrganisationnelleService uoService;

    @Secured("ROLE_USER")
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
}
