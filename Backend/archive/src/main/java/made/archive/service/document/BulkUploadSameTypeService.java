package made.archive.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.BulkFinalizeRequestDto;
import made.archive.dto.BulkOcrPreviewResponseDto;
import made.archive.dto.BulkUploadItemResultDto;
import made.archive.dto.BulkUploadReportDto;
import made.archive.dto.DocumentUploadDto;
import made.archive.dto.DocumentUploadResultDto;
import made.archive.dto.FinalizeUploadRequestDto;
import made.archive.dto.OcrPreviewResponseDto;
import made.archive.dto.WebImportOcrRequestDto;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.exception.PdfAConversionException;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


@Slf4j
@Service
@RequiredArgsConstructor
public class BulkUploadSameTypeService
{
    private final DocumentOcrService documentOcrService;
    private final DocumentUploadeService documentUploadService;
    private final OcrSessionCache ocrSessionCache;
    private final TypeDocumentRepository typeDocumentRepository;
    private final UserRepository userRepository;
    private final WebImportService webImportService;

    // ═══════════════════════════════════════════════════════════════
    // ÉTAPE 1 : OCR Preview sur N fichiers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Lance la Phase 1 OCR sur chaque fichier.
     * Retourne une liste de previews (sessionId + suggestions par fichier).
     * Les erreurs par fichier sont capturées sans interrompre le batch.
     */
    public BulkOcrPreviewResponseDto startOcrPreview(List<MultipartFile> files,
                                                      Long typeDocumentId,
                                                      UUID uploadedById)
    {
        typeDocumentRepository.findById(typeDocumentId)
            .orElseThrow(() -> new BusinessException(
                "Type de document introuvable : " + typeDocumentId));

        User uploadedBy = userRepository.findById(uploadedById)
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable : " + uploadedById));

        List<OcrPreviewResponseDto> previews = new ArrayList<>();

        for (MultipartFile file : files)
        {
            String nomFichier = file.getOriginalFilename();
            previews.add(traiterUnFichier(nomFichier,
                () -> documentOcrService.processOcrPreview(file, typeDocumentId, uploadedBy)));
        }

        return buildRapportPreview(previews);
    }

    /**
     * Variante "lien web" de la Phase 1 : télécharge chaque fichier confirmé par
     * l'utilisateur sur l'aperçu WebImportService.previewer, puis applique
     * exactement le même traitement OCR par fichier que l'upload navigateur.
     */
    public BulkOcrPreviewResponseDto startOcrPreviewFromWeb(WebImportOcrRequestDto requete)
    {
        typeDocumentRepository.findById(requete.getTypeDocumentId())
            .orElseThrow(() -> new BusinessException(
                "Type de document introuvable : " + requete.getTypeDocumentId()));

        User uploadedBy = userRepository.findById(requete.getUploadedById())
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable : " + requete.getUploadedById()));

        List<WebImportService.FichierDistant> fichiers = webImportService.telecharger(requete.getFichiersUrls());

        List<OcrPreviewResponseDto> previews = new ArrayList<>();

        for (WebImportService.FichierDistant fichier : fichiers)
        {
            previews.add(traiterUnFichier(fichier.nomFichier(),
                () -> documentOcrService.processOcrPreview(
                    fichier.nomFichier(), fichier.bytes(), requete.getTypeDocumentId(), uploadedBy)));
        }

        return buildRapportPreview(previews);
    }

    @FunctionalInterface
    private interface OcrSupplier
    {
        OcrSessionCache.OcrSessionData get() throws PdfAConversionException;
    }

    /**
     * Traite un fichier (quelle que soit sa source) et capture toute erreur sans
     * interrompre le batch — un fichier en échec n'a simplement pas de sessionId
     * dans le preview retourné.
     */
    private OcrPreviewResponseDto traiterUnFichier(String nomFichier, OcrSupplier supplier)
    {
        log.info("[BulkSameType-Phase1] Traitement : {}", nomFichier);

        try
        {
            OcrSessionCache.OcrSessionData sessionData = supplier.get();
            UUID sessionId = ocrSessionCache.storeSession(sessionData);

            log.info("[BulkSameType-Phase1] OK : {} → session {}", nomFichier, sessionId);

            return OcrPreviewResponseDto.builder()
                .sessionId(sessionId.toString())
                .nomFichier(nomFichier)
                .metaDataSuggestions(sessionData.suggestions)
                .message(buildPreviewMessage(nomFichier, sessionData))
                .build();
        }
        catch (PdfAConversionException e)
        {
            log.warn("[BulkSameType-Phase1] ERREUR PDF/A {} : {}", nomFichier, e.getMessage());
            return buildFailedPreview(nomFichier, "Échec conversion PDF/A : " + e.getMessage());
        }
        catch (BusinessException e)
        {
            log.warn("[BulkSameType-Phase1] ERREUR métier {} : {}", nomFichier, e.getMessage());
            return buildFailedPreview(nomFichier, e.getMessage());
        }
        catch (Exception e)
        {
            log.error("[BulkSameType-Phase1] ERREUR {} : {}", nomFichier, e.getMessage(), e);
            return buildFailedPreview(nomFichier, "Erreur inattendue : " + e.getMessage());
        }
    }

    private BulkOcrPreviewResponseDto buildRapportPreview(List<OcrPreviewResponseDto> previews)
    {
        long success = previews.stream().filter(p -> p.getSessionId() != null).count();
        long failed  = previews.size() - success;

        log.info("[BulkSameType-Phase1] Terminé — {} OK / {} ERREUR", success, failed);

        return BulkOcrPreviewResponseDto.builder()
            .total(previews.size())
            .success((int) success)
            .failed((int) failed)
            .previews(previews)
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // ÉTAPE 2 : Finalisation après validation client
    // ═══════════════════════════════════════════════════════════════

    /**
     * Finalise chaque document après validation des métadonnées par le client.
     * Chaque requête contient un sessionId + les métadonnées validées/corrigées.
     * Identique à l'upload unitaire mais appliqué en batch.
     */
    public BulkUploadReportDto finalizeAll(BulkFinalizeRequestDto bulkRequest)
    {
        List<FinalizeUploadRequestDto> requests = bulkRequest.getRequests();

        List<BulkUploadItemResultDto> details = new ArrayList<>();
        int success = 0;
        int failed  = 0;

        for (FinalizeUploadRequestDto request : requests)
        {
            // Récupérer le nom du fichier depuis la session pour les logs
            String nomFichier = resolveFilename(request.getSessionId());

            log.info("[BulkSameType-Phase2] Finalisation : {} (session: {})",
                     nomFichier, request.getSessionId());

            try
            {
                // Délégation complète à DocumentUploadeService.finalizeUpload()
                // → validation stricte des types, stockage PDF/A, OCR final, Meilisearch
                DocumentUploadResultDto result = documentUploadService.finalizeUpload(request);
                success++;

                details.add(BulkUploadItemResultDto.builder()
                    .nomFichier(nomFichier)
                    .typeDocument(resolveTypeName(request))
                    .status("SUCCESS")
                    .documentId(result.getDocumentId())
                    .build());

                log.info("[BulkSameType-Phase2] OK : {} → doc {}", nomFichier, result.getDocumentId());
            }
            catch (BusinessException e)
            {
                failed++;
                details.add(BulkUploadItemResultDto.builder()
                    .nomFichier(nomFichier)
                    .typeDocument(resolveTypeName(request))
                    .status("FAILED")
                    .erreur(e.getMessage())
                    .build());
                log.warn("[BulkSameType-Phase2] ERREUR {} : {}", nomFichier, e.getMessage());
            }
            catch (Exception e)
            {
                failed++;
                details.add(BulkUploadItemResultDto.builder()
                    .nomFichier(nomFichier)
                    .typeDocument(resolveTypeName(request))
                    .status("FAILED")
                    .erreur("Erreur inattendue : " + e.getMessage())
                    .build());
                log.error("[BulkSameType-Phase2] ERREUR {} : {}", nomFichier, e.getMessage(), e);
            }
        }

        log.info("[BulkSameType-Phase2] Terminé — {} OK / {} ERREUR", success, failed);

        return BulkUploadReportDto.builder()
            .total(requests.size())
            .success(success)
            .failed(failed)
            .details(details)
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers privés
    // ═══════════════════════════════════════════════════════════════

    /**
     * Tente de récupérer le nom du fichier depuis la session OCR (pour les logs/rapport).
     * Retourne "inconnu" si la session est déjà expirée ou absente.
     */
    private String resolveFilename(String sessionIdStr)
    {
        try
        {
            UUID sessionId = UUID.fromString(sessionIdStr);
            OcrSessionCache.OcrSessionData data = ocrSessionCache.getSession(sessionId);
            return data != null ? data.originalFilename : "inconnu";
        }
        catch (Exception e)
        {
            return "inconnu";
        }
    }

    /**
     * Récupère le nom du type de document depuis le DTO (pour les logs/rapport).
     */
    private String resolveTypeName(FinalizeUploadRequestDto request)
    {
        try
        {
            DocumentUploadDto dto = request.getDocumentUploadDto();
            return dto != null && dto.getTypeDocumentId() != null
                ? "type#" + dto.getTypeDocumentId()
                : "inconnu";
        }
        catch (Exception e)
        {
            return "inconnu";
        }
    }

    private String buildPreviewMessage(String nomFichier,
                                        OcrSessionCache.OcrSessionData sessionData)
    {
        if (sessionData.suggestions != null && !sessionData.suggestions.isEmpty())
        {
            return "✅ " + nomFichier + " — "
                + sessionData.suggestions.size() + " métadonnée(s) pré-remplie(s) via OCR.";
        }
        else if (!sessionData.regexAlreadyGenerated)
        {
            // Premier document de ce type : normal qu'il n'y ait aucune
            // suggestion, aucune règle d'extraction n'existe encore.
            return "ℹ️ " + nomFichier
                + " — Premier document de ce type, pas encore de règle d'extraction. "
                + "Remplissez manuellement.";
        }
        else if (sessionData.extractedText != null && !sessionData.extractedText.isBlank())
        {
            return "⚠️ " + nomFichier
                + " — Texte extrait mais aucune correspondance avec les règles existantes. "
                + "Remplissez manuellement.";
        }
        else
        {
            return "ℹ️ " + nomFichier + " — Aucun texte extractible. Remplissez manuellement.";
        }
    }

    private OcrPreviewResponseDto buildFailedPreview(String nomFichier, String erreur)
    {
        return OcrPreviewResponseDto.builder()
            .sessionId(null)
            .nomFichier(nomFichier)
            .metaDataSuggestions(null)
            .message("❌ " + nomFichier + " — " + erreur)
            .build();
    }
}