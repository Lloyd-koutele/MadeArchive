package made.archive.controller;

import java.nio.file.Path;
import java.util.UUID;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import made.archive.exception.BusinessException;
import made.archive.security.UserDetailsImpl;
import made.archive.service.document.DocumentExportService;

/**
 * Téléchargement d'un export déjà généré — contrôleur SÉPARÉ de
 * AdminDocumentExportController (déclenchement/suivi) pour que le
 * téléchargement, potentiellement un gros fichier streamé, ne partage pas
 * son chemin de code avec la création/le suivi de jobs — voir l'échange de
 * conception qui a précédé ce code.
 *
 * Revérifie l'autorité UO du demandeur à CHAQUE téléchargement (pas
 * seulement au lancement) — voir DocumentExportService.trouverJobAutorise.
 */
@RestController
@RequestMapping("/api/admin_uo/document-export")
@RequiredArgsConstructor
public class DocumentExportDownloadController
{
    private final DocumentExportService documentExportService;

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/{jobId}/telecharger")
    public ResponseEntity<?> telecharger(
        @PathVariable UUID jobId,
        @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            Path zip = documentExportService.getZipPourTelechargement(jobId, principal.getUser());

            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(jobId + ".zip").build().toString())
                .body(new FileSystemResource(zip));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Erreur lors du téléchargement de l'export : " + e.getMessage());
        }
    }
}
