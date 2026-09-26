package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.entite.Dossier;
import made.archive.security.UserDetailsImpl;
import made.archive.service.organisation.DossierService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Lecture seule des dossiers — séparé de DossierController (écriture, réservée
 * à EDITOR). Ouvert à ROLE_USER, le plancher de la hiérarchie des rôles :
 * EDITOR, ADMIN_UO et ADMIN en héritent déjà.
 *
 * Scopé en deux temps par DossierService : périmètre UO (ADMIN → tout ;
 * ADMIN_UO → son UO + descendantes ; EDITOR/USER → leur propre UO), ET
 * confidentialité (un dossier PRIVÉ n'est visible qu'à ses membres — aucune
 * exception de rôle, même pour ADMIN/ADMIN_UO). Cette double règle s'applique
 * aussi bien à la liste qu'au détail, pour qu'un dossier privé ne fuite jamais,
 * même indirectement (comptage, pagination...).
 */
@RestController
@RequestMapping("/api/user/dossiers")
@RequiredArgsConstructor
public class DossierLectureController
{
    private final DossierService dossierService;

    @Secured("ROLE_USER")
    @GetMapping("/uo/{uoId}")
    public ResponseEntity<?> getDossiersDeUO(
        @PathVariable Long uoId,
        @RequestParam(required = false) Long parentId,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            List<Dossier> dossiers = dossierService.getDossiersDeUO(uoId, parentId, currentUser.getUser());
            return ResponseEntity.ok(dossiers);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de la récupération des dossiers : " + e.getMessage()));
        }
    }

    /**
     * Arbre complet des dossiers de l'UO, à plat — pour un sélecteur
     * pliable/dépliable côté client (voir DossierArbreDto), typiquement le
     * choix d'un dossier cible à l'archivage.
     */
    @Secured("ROLE_USER")
    @GetMapping("/uo/{uoId}/arbre")
    public ResponseEntity<?> getArbreDossiers(
        @PathVariable Long uoId,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            return ResponseEntity.ok(dossierService.getArbreDossiers(uoId, currentUser.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de la récupération de l'arbre des dossiers : " + e.getMessage()));
        }
    }

    /**
     * Détail d'un dossier + checklist des types de documents attendus
     * ("2/4 fournis") + drapeaux peutGererTypes/peutGererAcces pour que le
     * client sache quels contrôles afficher pour l'utilisateur courant.
     */
    @Secured("ROLE_USER")
    @GetMapping("/{id}")
    public ResponseEntity<?> getDossierDetail(
        @PathVariable Long id,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            return ResponseEntity.ok(dossierService.getDossierDetail(id, currentUser.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de la récupération du dossier : " + e.getMessage()));
        }
    }
}
