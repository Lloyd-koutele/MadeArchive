package made.archive.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import made.archive.dto.BulkFinalizeRequestDto;
import made.archive.dto.BulkOcrPreviewResponseDto;
import made.archive.dto.BulkUploadReportDto;
import made.archive.dto.DocumentUploadResultDto;
import made.archive.dto.FinalizeUploadRequestDto;
import made.archive.dto.FtpImportRequestDto;
import made.archive.dto.OcrPreviewResponseDto;
import made.archive.entite.TypeDocument;
import made.archive.exception.BusinessException;
import made.archive.service.document.BulkUploadSameTypeService;
import made.archive.service.document.DocumentOcrService;
import made.archive.service.document.DocumentUploadeService;
import made.archive.service.document.OcrSessionCache;
import made.archive.security.UserDetailsImpl;
import made.archive.service.document.TypeDocumentService;
import made.archive.service.user.UserService;
import made.archive.util.TypeDocumentMapper;

@RestController
@RequestMapping("/api/editor")
@RequiredArgsConstructor
public class DocumentController
{
    private final DocumentUploadeService documentUploadeService;
    private final BulkUploadSameTypeService bulkUploadSameTypeService;
    private final TypeDocumentService typeDocumentService;
    private final TypeDocumentMapper typeDocumentMapper;
    private final UserService userService;
    private final DocumentOcrService documentOcrService;
    private final OcrSessionCache ocrSessionCache;

    // ═══════════════════════════════════════════════════════════════════
    // Types de documents
    // ═══════════════════════════════════════════════════════════════════

    @Secured("ROLE_EDITOR")
    @GetMapping("/types-documents")
    public ResponseEntity<?> getAllTypeDocuments()
    {
        try
        {
            List<TypeDocument> typeDocuments = typeDocumentService.getAllTypeDocuments();
            return ResponseEntity.ok(typeDocumentMapper.toDtoList(typeDocuments));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest()
                .body("Erreur lors de la récupération de tous les types de documents : "
                    + e.getMessage());
        }
    }

    @Secured("ROLE_EDITOR")
    @GetMapping("/types-documents/{id}")
    public ResponseEntity<?> getTypeDocumentById(@PathVariable Long id)
    {
        try
        {
            TypeDocument typeDocument = typeDocumentService.getTypeDocumentById(id);
            if (typeDocument == null)
            {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(typeDocumentMapper.toDto(typeDocument));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest()
                .body("Erreur lors de la récupération du type de document : " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Upload unitaire — Phase 1 : OCR Preview
    // ═══════════════════════════════════════════════════════════════════

    /**
     * POST /api/editor/ocr-preview
     * Lance l'OCR sur un fichier et retourne les suggestions de métadonnées.
     */
    @Secured("ROLE_EDITOR")
    @PostMapping("/ocr-preview")
    public ResponseEntity<OcrPreviewResponseDto> ocrPreview(
        @RequestParam("file") MultipartFile file,
        @RequestParam("typeDocumentId") Long typeDocumentId,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        if (file.isEmpty())
        {
            return ResponseEntity.badRequest()
                .body(OcrPreviewResponseDto.builder()
                    .message("Fichier vide")
                    .build());
        }

        try
        {
            OcrSessionCache.OcrSessionData sessionData = documentOcrService.processOcrPreview(
                file, typeDocumentId, currentUser.getUser());

            UUID sessionId = ocrSessionCache.storeSession(sessionData);

            return ResponseEntity.ok(OcrPreviewResponseDto.builder()
                .sessionId(sessionId.toString())
                .metaDataSuggestions(sessionData.suggestions)
                .message(determinePhase1Message(sessionData))
                .build());
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest()
                .body(OcrPreviewResponseDto.builder().message(e.getMessage()).build());
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(OcrPreviewResponseDto.builder()
                    .message("Erreur lors de l'OCR : " + e.getMessage())
                    .build());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Upload unitaire — Phase 2 : Finalisation
    // ═══════════════════════════════════════════════════════════════════

    /**
     * POST /api/editor/finalize-upload
     * Valide les métadonnées et crée le Document définitif.
     */
    @Secured("ROLE_EDITOR")
    @PostMapping("/finalize-upload")
    public ResponseEntity<?> finalizeUpload(
        @RequestBody FinalizeUploadRequestDto request,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        if (request.getSessionId() == null || request.getSessionId().isBlank())
        {
            return ResponseEntity.badRequest()
                .body(buildErrorResponse("SESSION_INVALID", "Session ID invalide"));
        }

        try
        {
            DocumentUploadResultDto result = documentUploadeService.finalizeUpload(request);
            return ResponseEntity.ok(result);
        }
        catch (BusinessException e)
        {
            if (e.getMessage().contains("Session expirée"))
            {
                return ResponseEntity.badRequest()
                    .body(buildErrorResponse("SESSION_EXPIRED", e.getMessage()));
            }
            else if (e.getMessage().contains("Validation"))
            {
                return ResponseEntity.badRequest()
                    .body(buildErrorResponse("VALIDATION_ERROR", e.getMessage()));
            }
            return ResponseEntity.badRequest()
                .body(buildErrorResponse("BUSINESS_ERROR", e.getMessage()));
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildErrorResponse("INTERNAL_ERROR",
                    "Erreur serveur : " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Bulk same-type — Phase 1 : OCR Preview sur N fichiers
    // ═══════════════════════════════════════════════════════════════════

    /**
     * POST /api/editor/docs/bulk/same-type/ocr-preview
     *
     * Reçoit N fichiers du même type et lance l'OCR Phase 1 sur chacun.
     * Retourne une liste de sessionId + suggestions, une par fichier.
     * Le client affiche les suggestions, permet la correction, puis appelle
     * /bulk/same-type/finalize avec les métadonnées validées.
     *
     * multipart/form-data :
     *   - files[]        : les fichiers à archiver
     *   - typeDocumentId : ID du type commun à tous les fichiers
     *   - uploadedById   : UUID de l'utilisateur connecté
     */
    @Secured("ROLE_EDITOR")
    @PostMapping(value = "/docs/bulk/same-type/ocr-preview",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> bulkSameTypeOcrPreview(
        @RequestPart("files") List<MultipartFile> files,
        @RequestParam("typeDocumentId") Long typeDocumentId,
        @RequestParam("uploadedById") UUID uploadedById)
    {
        if (files == null || files.isEmpty())
        {
            return ResponseEntity.badRequest().body("Aucun fichier fourni");
        }

        try
        {
            BulkOcrPreviewResponseDto response =
                bulkUploadSameTypeService.startOcrPreview(files, typeDocumentId, uploadedById);
            return ResponseEntity.ok(response);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest()
                .body(buildErrorResponse("BUSINESS_ERROR", e.getMessage()));
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildErrorResponse("INTERNAL_ERROR",
                    "Erreur OCR bulk : " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Bulk same-type — Phase 1 (source distante) : import FTP/FTPS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * POST /api/editor/docs/bulk/same-type/ftp/ocr-preview
     *
     * Variante de l'endpoint ci-dessus : au lieu de fichiers envoyés en
     * multipart, télécharge tous les fichiers d'un dossier distant (FTP ou
     * FTPS selon FtpImportRequestDto.secure) puis leur applique exactement
     * le même traitement OCR Phase 1. Retourne le même BulkOcrPreviewResponseDto
     * — le client enchaîne ensuite sur /bulk/same-type/finalize, inchangé.
     *
     * Les identifiants FTP fournis ne sont jamais persistés côté serveur.
     */
    @Secured("ROLE_EDITOR")
    @PostMapping("/docs/bulk/same-type/ftp/ocr-preview")
    public ResponseEntity<?> bulkSameTypeOcrPreviewFromFtp(@RequestBody FtpImportRequestDto requete)
    {
        try
        {
            BulkOcrPreviewResponseDto response =
                bulkUploadSameTypeService.startOcrPreviewFromFtp(requete);
            return ResponseEntity.ok(response);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest()
                .body(buildErrorResponse("BUSINESS_ERROR", e.getMessage()));
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildErrorResponse("INTERNAL_ERROR",
                    "Erreur import FTP : " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Bulk same-type — Phase 2 : Finalisation après validation client
    // ═══════════════════════════════════════════════════════════════════

    /**
     * POST /api/editor/docs/bulk/same-type/finalize
     *
     * Finalise chaque document après validation des métadonnées par le client.
     * Appelle DocumentUploadeService.finalizeUpload() pour chacun —
     * identique à l'upload unitaire, appliqué en batch.
     *
     * Body JSON (BulkFinalizeRequestDto) :
     * {
     *   "requests": [
     *     {
     *       "sessionId": "uuid-aaa",
     *       "documentUploadDto": { "titre": "...", "access": "PRIVE", ... },
     *       "metaDataValidated": [
     *         { "nom": "Date", "valeur": "2024-01-15", "typeValeur": "DATE" },
     *         ...
     *       ]
     *     },
     *     ...
     *   ]
     * }
     */
    @Secured("ROLE_EDITOR")
    @PostMapping("/docs/bulk/same-type/finalize")
    public ResponseEntity<?> bulkSameTypeFinalize(
        @RequestBody BulkFinalizeRequestDto bulkRequest,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        if (bulkRequest.getRequests() == null || bulkRequest.getRequests().isEmpty())
        {
            return ResponseEntity.badRequest()
                .body(buildErrorResponse("INVALID_REQUEST",
                    "La liste des requêtes de finalisation est vide"));
        }

        try
        {
            BulkUploadReportDto report = bulkUploadSameTypeService.finalizeAll(bulkRequest);
            return ResponseEntity.ok(report);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest()
                .body(buildErrorResponse("BUSINESS_ERROR", e.getMessage()));
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildErrorResponse("INTERNAL_ERROR",
                    "Erreur finalisation bulk : " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Utilisateurs
    // ═══════════════════════════════════════════════════════════════════

    /**
     * GET /api/editor/uo/{id}/users
     * Liste les utilisateurs de l'UO donnée (pour le choix des membres de groupe).
     */
    @Secured("ROLE_EDITOR")
    @GetMapping("/uo/{id}/users")
    public ResponseEntity<?> getUserByUO(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl principal)
    {
        try
        {
            return ResponseEntity.ok(userService.getUsersByUO(id, principal.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest()
                .body("Erreur récupération utilisateurs : " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helpers privés
    // ═══════════════════════════════════════════════════════════════════

    private String determinePhase1Message(OcrSessionCache.OcrSessionData sessionData)
    {
        if (sessionData.suggestions != null && !sessionData.suggestions.isEmpty())
        {
            return "✅ Métadonnées pré-remplies via analyse OCR. "
                + "Veuillez vérifier et corriger avant d'archiver.";
        }
        else if (!sessionData.regexAlreadyGenerated)
        {
            // Premier document de ce type : pas de règle d'extraction encore
            // générée, donc aucune suggestion possible — c'est normal, pas une
            // erreur d'OCR. La règle sera créée automatiquement après validation.
            return "ℹ️ Premier document de ce type : aucune règle d'extraction "
                + "n'existe encore. Veuillez remplir les métadonnées manuellement — "
                + "une règle sera générée automatiquement pour les prochains documents.";
        }
        else if (sessionData.extractedText != null && !sessionData.extractedText.isBlank())
        {
            return "⚠️ Le texte extrait ne correspond à aucune règle d'extraction "
                + "existante. Veuillez vérifier et remplir les champs manuellement.";
        }
        return "ℹ️ Analyse OCR terminée. Veuillez remplir les métadonnées.";
    }

    private Map<String, Object> buildErrorResponse(String errorCode, String message)
    {
        Map<String, Object> response = new HashMap<>();
        response.put("error", errorCode);
        response.put("message", message);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
}