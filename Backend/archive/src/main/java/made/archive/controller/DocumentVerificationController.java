package made.archive.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable ;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.DocumentMetadataDto;
import made.archive.dto.DocumentVerificationDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.security.PkiService;
import made.archive.service.audit.AuditLogService;

/**
 * Contrôleur PUBLIC pour vérifier l'authenticité des documents archivés
 * 
 * Endpoints :
 * - GET /api/public/verify/{documentId} → Vérifier une signature
 * - GET /api/public/documents/{documentId} → Obtenir métadonnées du document
 */
@Slf4j
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class DocumentVerificationController
{
    private final DocumentRepository documentRepository;
    private final PkiService pkiService;
    private final AuditLogService auditLogService;

    /**
     * Vérifie l'authenticité d'un document
     * 
     * Endpoint PUBLIC - Accessible à tous
     * 
     * @param documentId UUID du document à vérifier
     * @return Résultat de vérification (authentique / falsifié / erreur)
     */
    @GetMapping("/verify/{documentId}")
    public ResponseEntity<?> verifyDocument(@PathVariable String documentId)
    {
        try
        {
            UUID docId = UUID.fromString(documentId);
            
            // 1. Récupérer le document
            Document document = documentRepository.findById(docId)
                .orElseThrow(() -> new BusinessException("Document non trouvé : " + docId));

            // 2. Vérifier que tous les champs nécessaires existent
            String publicKey = document.getUploadedBy() != null
                ? document.getUploadedBy().getPkiPublicKey() : null;

            if (publicKey == null || publicKey.isBlank())
            {
                return ResponseEntity.badRequest().body(new DocumentVerificationDto(
                    false,
                    "Document non signé",
                    "L'éditeur ayant déposé ce document n'a pas de clé PKI publique",
                    null
                ));
            }

            if (document.getPkiSignature() == null || document.getPkiSignature().isBlank())
            {
                return ResponseEntity.badRequest().body(new DocumentVerificationDto(
                    false,
                    "Signature manquante",
                    "La signature PKI du document est manquante",
                    null
                ));
            }

            // 3. Vérifier la signature (avec la clé publique de l'éditeur qui a déposé)
            boolean isValid = pkiService.verifySignature(
                document.getPdfaSha256(),
                document.getPkiSignature(),
                publicKey
            );

            // 4. Retourner le résultat
            String status = isValid ? "AUTHENTIQUE ✓" : "FALSIFIÉ ✗";
            String message = isValid 
                ? "La signature est valide. Le document n'a pas été modifié."
                : "La signature est invalide. Le document a été altéré.";

            log.info("[Verification] Document {} : {}", docId, status);

            // Acteur anonyme : endpoint public, accessible sans authentification.
            auditLogService.log(null, AuditAction.DOCUMENT_VERIFICATION_PUBLIQUE, AuditCible.DOCUMENT,
                docId.toString(),
                document.getUniteOrganisationnelle() != null ? document.getUniteOrganisationnelle().getId() : null,
                "Vérification publique d'authenticité du document \"" + document.getTitre() + "\" — " + status,
                isValid);

            return ResponseEntity.ok(new DocumentVerificationDto(
                isValid,
                status,
                message,
                DocumentVerificationDto.DocumentMetadata.fromEntity(document)
            ));
        }
        catch (IllegalArgumentException e)
        {
            return ResponseEntity.badRequest()
                .body(new DocumentVerificationDto(false, "Erreur", "ID de document invalide", null));
        }
        catch (Exception e)
        {
            log.error("[Verification] Erreur : {}", e.getMessage());
            return ResponseEntity.internalServerError()
                .body(new DocumentVerificationDto(false, "Erreur serveur", e.getMessage(), null));
        }
    }

    /**
     * Obtient les métadonnées publiques d'un document
     * (pour vérification manuelle avec la clé publique)
     */
    @GetMapping("/documents/{documentId}")
    public ResponseEntity<?> getDocumentMetadata(@PathVariable String documentId)
    {
        try
        {
            UUID docId = UUID.fromString(documentId);
            
            Document document = documentRepository.findById(docId)
                .orElseThrow(() -> new BusinessException("Document non trouvé"));

            String publicKey = document.getUploadedBy() != null
                ? document.getUploadedBy().getPkiPublicKey() : null;

            return ResponseEntity.ok(new DocumentMetadataDto(
                document.getId(),
                document.getTitre(),
                document.getPdfaSha256(),
                publicKey,
                document.getPkiSignature(),
                document.getCreateAt(),
                document.getRetentionUntil()
            ));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Erreur : " + e.getMessage()));
        }
    }
}