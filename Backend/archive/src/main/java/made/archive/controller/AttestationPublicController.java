package made.archive.controller;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import made.archive.exception.BusinessException;
import made.archive.service.document.AttestationService;

/**
 * Contrôleur PUBLIC — consultation/téléchargement du PDF d'une attestation
 * d'archivage à partir de son jeton (pas l'UUID réel du document). Aucune
 * authentification requise (voir SecurityConfig, /api/public/** permitAll) ;
 * ne donne jamais accès à autre chose que ce PDF reconstruit à la volée, et
 * ne change jamais le statut d'accès du document sous-jacent.
 *
 * Endpoints :
 * - GET /api/public/attestation/{token}/view     → PDF inline (visionneuse)
 * - GET /api/public/attestation/{token}/download → PDF en téléchargement
 */
@RestController
@RequestMapping("/api/public/attestation")
@RequiredArgsConstructor
public class AttestationPublicController
{
    private final AttestationService attestationService;

    @GetMapping("/{token}/view")
    public ResponseEntity<byte[]> voir(@PathVariable String token)
    {
        try
        {
            byte[] pdf = attestationService.genererPdfPourToken(token);
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.inline().filename("attestation.pdf").build().toString())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdf.length))
                .header("X-Frame-Options", "SAMEORIGIN")
                .body(pdf);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/{token}/download")
    public ResponseEntity<byte[]> telecharger(@PathVariable String token)
    {
        try
        {
            byte[] pdf = attestationService.genererPdfPourToken(token);
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename("attestation.pdf").build().toString())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdf.length))
                .body(pdf);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}
