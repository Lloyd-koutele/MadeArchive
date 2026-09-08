package made.archive.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import made.archive.dto.FixityCheckRequestDto;
import made.archive.exception.BusinessException;
import made.archive.service.document.FixityCheckTriggerService;

/**
 * Déclenchement MANUEL du contrôle d'intégrité (fixity check) — voir
 * FixityCheckTriggerService pour les règles d'autorité, de périmètre et
 * d'anti-rafale (cooldown de 6h par périmètre individuel).
 *
 * Un seul endpoint pour les trois périmètres (TYPES/UO réservés à
 * ROLE_ADMIN_UO scopé à son autorité, TOUT réservé à ROLE_ADMIN) — la
 * distinction est vérifiée dans le service, pas ici, pour ne pas dupliquer
 * la logique d'autorité entre deux contrôleurs.
 */
@RestController
@RequestMapping("/api/admin_uo/fixity-check")
@RequiredArgsConstructor
public class FixityCheckController
{
    private final FixityCheckTriggerService fixityCheckTriggerService;

    @Secured("ROLE_ADMIN_UO")
    @PostMapping
    public ResponseEntity<?> declencher(
        @RequestBody FixityCheckRequestDto request,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        FixityCheckTriggerService.Portee portee;
        try
        {
            portee = FixityCheckTriggerService.Portee.valueOf(
                request.getScope() != null ? request.getScope().toUpperCase() : "");
        }
        catch (IllegalArgumentException e)
        {
            return ResponseEntity.badRequest()
                .body(Map.of("message", "Périmètre invalide — attendu : TYPES, UO ou TOUT"));
        }

        try
        {
            fixityCheckTriggerService.declencher(
                portee, request.getTypeDocumentIds(), request.getUoIds(), userDetails);

            return ResponseEntity.ok(Map.of(
                "message", "Vérification lancée en arrière-plan — vous serez notifié une fois terminée."));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }
}
