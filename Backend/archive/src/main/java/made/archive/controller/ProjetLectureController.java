package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.entite.Projet;
import made.archive.security.UserDetailsImpl;
import made.archive.service.organisation.ProjetService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Lecture seule des projets — séparé de ProjetController (écriture, réservée
 * à EDITOR). Ouvert à ROLE_USER, le plancher de la hiérarchie des rôles :
 * EDITOR, ADMIN_UO et ADMIN en héritent déjà.
 *
 * Scopé en deux temps par ProjetService : périmètre UO (ADMIN → tout ;
 * ADMIN_UO → son UO + descendantes ; EDITOR/USER → leur propre UO), ET
 * confidentialité (un projet PRIVÉ n'est visible qu'à ses membres — aucune
 * exception de rôle, même pour ADMIN/ADMIN_UO). Cette double règle s'applique
 * aussi bien à la liste qu'au détail, pour qu'un projet privé ne fuite jamais,
 * même indirectement (comptage, pagination...).
 */
@RestController
@RequestMapping("/api/user/projets")
@RequiredArgsConstructor
public class ProjetLectureController
{
    private final ProjetService projetService;

    @Secured("ROLE_USER")
    @GetMapping("/uo/{uoId}")
    public ResponseEntity<?> getProjetsDeUO(
        @PathVariable Long uoId,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            List<Projet> projets = projetService.getProjetsDeUO(uoId, currentUser.getUser());
            return ResponseEntity.ok(projets);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de la récupération des projets : " + e.getMessage()));
        }
    }

    /**
     * Détail d'un projet + checklist des types de documents attendus
     * ("2/4 fournis") + drapeaux peutGererTypes/peutGererAcces pour que le
     * client sache quels contrôles afficher pour l'utilisateur courant.
     */
    @Secured("ROLE_USER")
    @GetMapping("/{id}")
    public ResponseEntity<?> getProjetDetail(
        @PathVariable Long id,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            return ResponseEntity.ok(projetService.getProjetDetail(id, currentUser.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de la récupération du projet : " + e.getMessage()));
        }
    }
}
