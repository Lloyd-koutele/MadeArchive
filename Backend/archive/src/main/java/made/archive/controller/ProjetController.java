package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.dto.ProjetDto;
import made.archive.entite.Projet;
import made.archive.security.UserDetailsImpl;
import made.archive.service.organisation.ProjetService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Écriture sur les projets — réservée à ROLE_EDITOR, exclusivement.
 *
 * Le projet est entièrement piloté par l'éditeur qui l'a créé : création,
 * gestion des types de documents attendus, suppression, et (via
 * ProjetGroupeAccessController) gestion de la confidentialité. ADMIN et
 * ADMIN_UO n'ont plus aucun droit d'écriture ici — voir ProjetLectureController
 * pour leur droit de lecture (scopé par UO et par confidentialité).
 */
@RestController
@RequestMapping("/api/editor/projets")
@RequiredArgsConstructor
public class ProjetController
{
    private final ProjetService projetService;

    @Secured("ROLE_EDITOR")
    @PostMapping
    public ResponseEntity<?> creerProjet(
        @RequestBody ProjetDto dto,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            Projet projet = projetService.creerProjet(dto, currentUser.getUser());
            return ResponseEntity.ok(projet);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de la création du projet : " + e.getMessage()));
        }
    }

    /**
     * Modifie le nom/la description d'un projet existant — jamais les types
     * attendus (voir /types ci-dessous) ni l'accès (voir
     * ProjetGroupeAccessController), toujours des appels séparés. Mêmes
     * droits que gérer les types attendus ou supprimer (voir
     * ProjetService.verifierPeutGererProjet).
     */
    @Secured("ROLE_EDITOR")
    @PutMapping("/{id}")
    public ResponseEntity<?> modifierProjet(
        @PathVariable Long id,
        @RequestBody ProjetDto dto,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            Projet projet = projetService.modifierProjet(id, dto.getNom(), dto.getDescription(), currentUser.getUser());
            return ResponseEntity.ok(projet);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de la modification du projet : " + e.getMessage()));
        }
    }

    /**
     * Ajoute des types de documents attendus à un projet existant (déjà créé,
     * potentiellement vide) — additif, pas de remplacement. Ouvert à tout
     * éditeur de la propre UO du projet (pas seulement son créateur).
     */
    @Secured("ROLE_EDITOR")
    @PostMapping("/{id}/types")
    public ResponseEntity<?> ajouterTypesAttendus(
        @PathVariable Long id,
        @RequestBody List<Long> typeDocumentIds,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            Projet projet = projetService.ajouterTypesAttendus(id, typeDocumentIds, currentUser.getUser());
            return ResponseEntity.ok(projet);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de l'ajout des types attendus : " + e.getMessage()));
        }
    }

    /**
     * Retire un type de document attendu d'un projet — refusé si des
     * documents de ce type existent déjà DANS CE PROJET (voir ProjetService).
     */
    @Secured("ROLE_EDITOR")
    @DeleteMapping("/{id}/types/{typeId}")
    public ResponseEntity<?> retirerTypeAttendu(
        @PathVariable Long id,
        @PathVariable Long typeId,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            Projet projet = projetService.retirerTypeAttendu(id, typeId, currentUser.getUser());
            return ResponseEntity.ok(projet);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors du retrait du type attendu : " + e.getMessage()));
        }
    }

    /**
     * Supprime un projet — uniquement s'il est vide (aucun document rattaché).
     * Si le projet est privé, réservé à un éditeur membre de son groupe
     * d'accès ; sinon, tout éditeur de la propre UO du projet (voir
     * ProjetService.supprimerProjet).
     */
    @Secured("ROLE_EDITOR")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> supprimerProjet(
        @PathVariable Long id,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            projetService.supprimerProjet(id, currentUser.getUser());
            return ResponseEntity.ok(Map.of("success", true));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de la suppression du projet : " + e.getMessage()));
        }
    }
}
