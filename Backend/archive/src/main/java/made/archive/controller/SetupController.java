package made.archive.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import made.archive.dto.SetupAdminRequestDto;
import made.archive.dto.SetupStatusDto;
import made.archive.exception.BusinessException;
import made.archive.service.user.SetupService;

/**
 * Assistant de première configuration — PUBLIC (voir /api/public/** dans
 * SecurityConfig), volontairement : au tout premier démarrage, personne ne
 * peut encore s'authentifier, il n'existe aucun compte. La sécurité vient
 * d'ailleurs : SetupService.creerAdminInitial refuse tout appel dès qu'un
 * admin existe déjà, quelle que soit la façon dont il a été créé — cette
 * fenêtre "publique" se referme définitivement après le tout premier usage.
 */
@RestController
@RequestMapping("/api/public/setup")
@RequiredArgsConstructor
public class SetupController
{
    private final SetupService setupService;

    /**
     * GET /api/public/setup/status
     * Le frontend interroge ceci avant d'afficher soit l'assistant, soit
     * l'écran de connexion normal.
     */
    @GetMapping("/status")
    public ResponseEntity<SetupStatusDto> statut()
    {
        return ResponseEntity.ok(new SetupStatusDto(setupService.needsSetup()));
    }

    /**
     * POST /api/public/setup/admin
     * Crée l'administrateur initial — une seule fois, voir SetupService.
     */
    @PostMapping("/admin")
    public ResponseEntity<?> creerAdmin(@Valid @RequestBody SetupAdminRequestDto dto)
    {
        try
        {
            setupService.creerAdminInitial(dto);
            return ResponseEntity.ok().build();
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Erreur lors de la création de l'administrateur : " + e.getMessage());
        }
    }
}
