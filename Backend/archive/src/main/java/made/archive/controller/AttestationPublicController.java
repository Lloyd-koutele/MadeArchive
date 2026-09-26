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
import made.archive.entite.Document;
import made.archive.exception.BusinessException;
import made.archive.service.document.AttestationService;
import made.archive.service.document.DocumentService;

/**
 * Contrôleur PUBLIC — consultation/téléchargement du PDF d'une attestation
 * d'archivage, ET du DOCUMENT ORIGINAL qu'elle atteste, à partir du même
 * jeton (pas l'UUID réel du document). Aucune authentification requise (voir
 * SecurityConfig, /api/public/** permitAll).
 *
 * Le document original reste accessible via ce jeton même s'il est PRIVÉ —
 * décision produit assumée (revue le 09/2026, le QR de l'attestation menait
 * auparavant uniquement à l'attestation elle-même, jamais au document) : ne
 * change en revanche jamais le statut d'accès normal du document (toujours
 * PRIVÉ pour un accès authentifié classique, voir DocumentService.resolveDocument).
 *
 * Endpoints :
 * - GET /api/public/attestation/{token}/view              → PDF de l'attestation, inline
 * - GET /api/public/attestation/{token}/download           → PDF de l'attestation, téléchargement
 * - GET /api/public/attestation/{token}/document/view      → PDF/A original, inline UNIQUEMENT
 *   (pas de /document/download — revu le 09/2026 : consultation seule depuis
 *   cette source publique, jamais de téléchargement du fichier original).
 */
@RestController
@RequestMapping("/api/public/attestation")
@RequiredArgsConstructor
public class AttestationPublicController
{
    private final AttestationService attestationService;
    private final DocumentService documentService;

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

    /**
     * PDF/A ORIGINAL (pas l'attestation) — destination réelle du QR imprimé
     * sur l'attestation, voir AttestationService.genererPdfPourToken.
     */
    @GetMapping("/{token}/document/view")
    public ResponseEntity<byte[]> voirDocument(@PathVariable String token)
    {
        try
        {
            Document doc = attestationService.resolveDocumentPourToken(token);
            byte[] bytes = documentService.lireBytesPdfAViaAttestation(doc);
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.inline().filename("document.pdf").build().toString())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length))
                .header("X-Frame-Options", "SAMEORIGIN")
                .body(bytes);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

}
