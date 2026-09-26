package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.dto.ChangerAccesRequestDto;
import made.archive.dto.DossierDeplacementPreviewDto;
import made.archive.dto.DossierDeplacerRequestDto;
import made.archive.dto.DossierDto;
import made.archive.entite.Dossier;
import made.archive.security.UserDetailsImpl;
import made.archive.service.organisation.DossierService;
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

import java.util.List;
import java.util.Map;

/**
 * Écriture sur les dossiers — réservée à ROLE_EDITOR, exclusivement.
 *
 * Le dossier est entièrement piloté par l'éditeur qui l'a créé : création,
 * gestion des types de documents attendus, suppression, et (via
 * DossierGroupeAccessController) gestion de la confidentialité. ADMIN et
 * ADMIN_UO n'ont plus aucun droit d'écriture ici — voir DossierLectureController
 * pour leur droit de lecture (scopé par UO et par confidentialité).
 */
@RestController
@RequestMapping("/api/editor/dossiers")
@RequiredArgsConstructor
public class DossierController
{
    private final DossierService dossierService;

    @Secured("ROLE_EDITOR")
    @PostMapping
    public ResponseEntity<?> creerDossier(
        @RequestBody DossierDto dto,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            Dossier dossier = dossierService.creerDossier(dto, currentUser.getUser());
            return ResponseEntity.ok(dossier);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de la création du dossier : " + e.getMessage()));
        }
    }

    /**
     * Modifie le nom d'un dossier existant — jamais les types attendus (voir
     * /types ci-dessous) ni l'accès (voir DossierGroupeAccessController),
     * toujours des appels séparés. Mêmes droits que gérer les types attendus
     * ou supprimer (voir DossierService.verifierPeutGererDossier).
     */
    @Secured("ROLE_EDITOR")
    @PutMapping("/{id}")
    public ResponseEntity<?> modifierDossier(
        @PathVariable Long id,
        @RequestBody DossierDto dto,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            Dossier dossier = dossierService.modifierDossier(id, dto.getNom(), currentUser.getUser());
            return ResponseEntity.ok(dossier);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de la modification du dossier : " + e.getMessage()));
        }
    }

    /**
     * Bascule PUBLIC ↔ PRIVÉ après coup — voir DossierService.modifierAcces
     * pour l'autorité requise et l'effet sur les documents du dossier.
     * groupeMembresIds n'a d'effet que si access passe à PRIVE (membres
     * initiaux du nouveau groupe, en plus de l'acteur).
     */
    @Secured("ROLE_EDITOR")
    @PutMapping("/{id}/acces")
    public ResponseEntity<?> modifierAcces(
        @PathVariable Long id,
        @RequestBody ChangerAccesRequestDto dto,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            Dossier dossier = dossierService.modifierAcces(id, dto, currentUser.getUser());
            return ResponseEntity.ok(dossier);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors du changement d'accès du dossier : " + e.getMessage()));
        }
    }

    /**
     * Cascade OPTIONNELLE PUBLIC vers toute la descendance — jamais
     * automatique (voir DossierService.modifierAcces) : à appeler séparément,
     * seulement si l'éditeur confirme dans le modal d'alerte affiché après
     * avoir rendu CE dossier public. Refusé si ce dossier n'est pas déjà
     * public lui-même.
     */
    @Secured("ROLE_EDITOR")
    @PutMapping("/{id}/acces/cascader-public")
    public ResponseEntity<?> cascaderAccesPublicVersDescendants(
        @PathVariable Long id,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            Dossier dossier = dossierService.cascaderAccesPublicVersDescendants(id, currentUser.getUser());
            return ResponseEntity.ok(dossier);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de la cascade vers les sous-dossiers : " + e.getMessage()));
        }
    }

    /**
     * Aperçu (dry-run) d'un déplacement — à appeler AVANT /deplacer pour
     * savoir si le client doit afficher un modal d'alerte (le dossier
     * deviendra privé, ou son groupe diverge de celui du nouveau parent —
     * voir DossierService.previsualiserDeplacement). nouveauParentId absent =
     * aperçu d'un déplacement vers la racine de l'UO.
     */
    @Secured("ROLE_EDITOR")
    @GetMapping("/{id}/deplacer/previsualiser")
    public ResponseEntity<?> previsualiserDeplacement(
        @PathVariable Long id,
        @RequestParam(required = false) Long nouveauParentId,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            DossierDeplacementPreviewDto preview =
                dossierService.previsualiserDeplacement(id, nouveauParentId, currentUser.getUser());
            return ResponseEntity.ok(preview);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de l'aperçu du déplacement : " + e.getMessage()));
        }
    }

    /**
     * Déplace un dossier vers un nouveau parent (glisser-déposer) — voir
     * DossierService.deplacerDossier. nouveauParentId absent/null = déplace
     * vers la racine de l'UO.
     */
    @Secured("ROLE_EDITOR")
    @PutMapping("/{id}/deplacer")
    public ResponseEntity<?> deplacerDossier(
        @PathVariable Long id,
        @RequestBody DossierDeplacerRequestDto dto,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            Dossier dossier = dossierService.deplacerDossier(id, dto.getNouveauParentId(), currentUser.getUser());
            return ResponseEntity.ok(dossier);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors du déplacement du dossier : " + e.getMessage()));
        }
    }

    /**
     * Ajoute des types de documents attendus à un dossier existant (déjà créé,
     * potentiellement vide) — additif, pas de remplacement. Ouvert à tout
     * éditeur de la propre UO du dossier (pas seulement son créateur).
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
            Dossier dossier = dossierService.ajouterTypesAttendus(id, typeDocumentIds, currentUser.getUser());
            return ResponseEntity.ok(dossier);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de l'ajout des types attendus : " + e.getMessage()));
        }
    }

    /**
     * Retire un type de document attendu d'un dossier — refusé si des
     * documents de ce type existent déjà DANS CE DOSSIER (voir DossierService).
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
            Dossier dossier = dossierService.retirerTypeAttendu(id, typeId, currentUser.getUser());
            return ResponseEntity.ok(dossier);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors du retrait du type attendu : " + e.getMessage()));
        }
    }

    /**
     * Supprime un dossier — uniquement s'il est vide (aucun document rattaché).
     * Si le dossier est privé, réservé à un éditeur membre de son groupe
     * d'accès ; sinon, tout éditeur de la propre UO du dossier (voir
     * DossierService.supprimerDossier).
     */
    @Secured("ROLE_EDITOR")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> supprimerDossier(
        @PathVariable Long id,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            dossierService.supprimerDossier(id, currentUser.getUser());
            return ResponseEntity.ok(Map.of("success", true));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Erreur lors de la suppression du dossier : " + e.getMessage()));
        }
    }
}
