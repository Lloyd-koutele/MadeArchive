package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.service.document.DocumentRetentionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Opérations de maintenance système ponctuelles — pas liées à une UO précise,
 * réservées à ADMIN. Pour l'instant : resynchronisation Meilisearch (voir
 * DocumentRetentionService.resynchroniserMeilisearch()).
 */
@RestController
@RequestMapping("/api/admin/maintenance")
@RequiredArgsConstructor
public class SystemMaintenanceController
{
    private final DocumentRetentionService documentRetentionService;

    /**
     * Retire de Meilisearch toute entrée dont le document est absent ou déjà
     * supprimé en base — utile après une intervention manuelle (base ou MinIO
     * modifiés directement, en dehors de l'application) qui aurait laissé des
     * résultats de recherche "fantômes". Idempotent, sans risque à relancer.
     */
    @Secured("ROLE_ADMIN")
    @PostMapping("/resync-meilisearch")
    public ResponseEntity<?> resyncMeilisearch()
    {
        try
        {
            int retirees = documentRetentionService.resynchroniserMeilisearch();
            return ResponseEntity.ok(Map.of(
                "entreesFantomesRetirees", retirees,
                "message", retirees > 0
                    ? retirees + " entrée(s) fantôme(s) retirée(s) de Meilisearch"
                    : "Rien à nettoyer, Meilisearch est déjà cohérent avec la base"
            ));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(Map.of(
                "message", "Erreur lors de la resynchronisation Meilisearch : " + e.getMessage()));
        }
    }
}
