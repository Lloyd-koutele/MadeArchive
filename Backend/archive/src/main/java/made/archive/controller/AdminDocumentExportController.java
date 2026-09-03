package made.archive.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import made.archive.dto.ExportApercuDocumentDto;
import made.archive.dto.ExportApercuRequestDto;
import made.archive.dto.ExportJobStatutDto;
import made.archive.dto.ExportLancerRequestDto;
import made.archive.entite.ExportJob;
import made.archive.exception.BusinessException;
import made.archive.security.UserDetailsImpl;
import made.archive.service.document.DocumentExportService;

/**
 * Export administratif de documents d'une ou plusieurs UO — pour une migration
 * ou un changement de système d'archivage (voir DocumentExportService pour
 * le détail des règles d'accès, volontairement à deux niveaux).
 *
 * ROLE_ADMIN voit toutes les UO ; ROLE_ADMIN_UO est automatiquement restreint
 * à son UO + sous-arbre (même scoping que AuditLogController) — mais ne peut
 * jamais inclure un document privé dont il n'est pas membre, seul ROLE_ADMIN
 * le peut (includePriveNonMembre, vérifié côté service, pas ici).
 */
@RestController
@RequestMapping("/api/admin_uo/document-export")
@RequiredArgsConstructor
public class AdminDocumentExportController
{
    private final DocumentExportService documentExportService;

    /**
     * POST /api/admin_uo/document-export/apercu
     * Liste les documents du périmètre demandé, SANS rien générer — pour
     * confirmation avant de lancer réellement l'export.
     */
    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PostMapping("/apercu")
    public ResponseEntity<?> apercu(
        @RequestBody ExportApercuRequestDto requete,
        @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            List<ExportApercuDocumentDto> documents = documentExportService.apercu(requete, principal.getUser());
            return ResponseEntity.ok(documents);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Erreur lors de l'aperçu de l'export : " + e.getMessage());
        }
    }

    /**
     * POST /api/admin_uo/document-export/lancer
     * Crée le job et démarre la génération du ZIP en tâche de fond — voir
     * DocumentExportGenerationService. Retourne immédiatement, avec le
     * statut initial (EN_ATTENTE, aussitôt basculé EN_COURS).
     */
    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PostMapping("/lancer")
    public ResponseEntity<?> lancer(
        @RequestBody ExportLancerRequestDto requete,
        @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            ExportJob job = documentExportService.lancerExport(requete, principal.getUser());
            return ResponseEntity.ok(documentExportService.getStatut(job.getId(), principal.getUser()));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Erreur lors du lancement de l'export : " + e.getMessage());
        }
    }

    /**
     * GET /api/admin_uo/document-export/{jobId}/statut
     * À interroger périodiquement par le client pendant la génération.
     */
    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/{jobId}/statut")
    public ResponseEntity<?> statut(
        @PathVariable UUID jobId,
        @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            ExportJobStatutDto statut = documentExportService.getStatut(jobId, principal.getUser());
            return ResponseEntity.ok(statut);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Erreur lors de la consultation du statut : " + e.getMessage());
        }
    }
}
