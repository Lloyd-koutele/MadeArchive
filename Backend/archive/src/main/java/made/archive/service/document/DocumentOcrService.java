package made.archive.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.PkiKeyStatus;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.exception.PdfAConversionException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UserRepository;
import made.archive.service.organisation.UniteOrganisationnelleService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;



@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentOcrService
{
    private final LibreOfficeConversionService libreOfficeConversionService;
    private final PdfAConversionService        pdfAConversionService;
    private final OcrService                   ocrService;
    private final HashService                  hashService;
    private final TypeDocumentRepository       typeDocumentRepository;
    private final DocumentRepository           documentRepository;
    private final UniteOrganisationnelleService uniteOrganisationnelleService;
    private final UserRepository               userRepository;
    private final OcrPositionalExtractionService ocrPositionalExtractionService;

    /**
     * Point d'entrée historique — upload navigateur (multipart/form-data).
     * Ne fait que lire le fichier puis déléguer à la surcharge sur bytes bruts.
     */
    public OcrSessionCache.OcrSessionData processOcrPreview(
        MultipartFile file,
        Long typeDocumentId,
        User uploadedBy)
        throws PdfAConversionException
    {
        try
        {
            return processOcrPreview(file.getOriginalFilename(), file.getBytes(), typeDocumentId, uploadedBy);
        }
        catch (PdfAConversionException | BusinessException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new BusinessException("Erreur OCR preview : " + e.getMessage(), e);
        }
    }

    /**
     * Cœur de la Phase 1 OCR — indépendant du transport (upload navigateur, dossier
     * local, import via lien…). Toute source de fichiers converge ici dès qu'elle dispose
     * du nom original et des bytes bruts.
     */
    public OcrSessionCache.OcrSessionData processOcrPreview(
        String originalFilename,
        byte[] originalBytes,
        Long typeDocumentId,
        User uploadedBy)
        throws PdfAConversionException
    {
        log.info("[OCR-Phase1] Démarrage pour type: {}, fichier: {}",
                 typeDocumentId, originalFilename);

        try
        {
            // ── 2. SHA-256 du fichier ORIGINAL ──────────────────────────────
            String originalSha256 = hashService.calculateFromBytes(originalBytes);
            log.info("[OCR-Phase1] Original SHA-256 : {}", originalSha256);

            // ── 2b. Charger le TypeDocument (fail-fast s'il n'existe pas) ────
            TypeDocument typeDocument = typeDocumentRepository
                .findById(typeDocumentId)
                .orElseThrow(() -> new BusinessException(
                    "Type de document introuvable : " + typeDocumentId));

            // ── 2c. Anti-doublon PRÉCOCE, scopé par UO ───────────────────────
            // Remonté ici (avant conversion PDF/A + OCR, coûteuses) pour
            // prévenir l'utilisateur immédiatement, plutôt qu'à la Phase 2
            // après qu'il ait rempli toutes les métadonnées.
            UniteOrganisationnelle uo =
                uniteOrganisationnelleService.getUOActuelleEntite(uploadedBy.getId());
            if (documentRepository.existsByOriginalSha256AndUniteOrganisationnelle_Id(
                    originalSha256, uo.getId()))
            {
                throw new BusinessException(
                    "Ce document existe déjà en archive pour votre unité organisationnelle");
            }

            // ── 2d. Éligibilité PKI PRÉCOCE ───────────────────────────────────
            // Même logique que l'anti-doublon ci-dessus : sans ça, un éditeur
            // sans clé active remplissait tout le formulaire de métadonnées
            // avant de se faire recaler seulement à la Phase 2 (finalize).
            //
            // Re-fetch INDISPENSABLE : uploadedBy vient ici de
            // UserDetailsImpl.getUser() résolu par JwtAuthFilter via
            // AuthCacheService (cache Redis) — cet objet reconstruit ne porte
            // QUE id/email/nom/prenom/actif/sessionInvalidatedAt/roles (voir
            // AuthCacheService.toUserDetails), pas les champs PKI, qui
            // retombent donc silencieusement sur leur défaut Java
            // (PkiKeyStatus.NONE) quel que soit l'état réel en base. Sans ce
            // re-fetch, TOUT éditeur échoue systématiquement cette
            // vérification, même avec une clé ACTIVE en base.
            User uploadedByFrais = userRepository.findById(uploadedBy.getId())
                .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));

            if (uploadedByFrais.getPkiKeyStatus() != PkiKeyStatus.ACTIVE
                || uploadedByFrais.getPkiKeyAlias() == null || uploadedByFrais.getPkiKeyAlias().isBlank())
            {
                throw new BusinessException(
                    "Vous ne possédez pas de clé de signature PKI active. "
                    + "Contactez un administrateur.");
            }

            // ── 3. Conversion en PDF via LibreOffice ─────────────────────────
            byte[] pdfBytes = libreOfficeConversionService.convertToPdf(
                originalBytes, originalFilename);

            // ── 4. Marquage PDF/A-3b ─────────────────────────────────────────
            byte[] pdfABytes = pdfAConversionService.convertToPdfA3(pdfBytes);

            // ── 5. SHA-256 du PDF/A-3b ───────────────────────────────────────
            String pdfaSha256 = hashService.calculateFromBytes(pdfABytes);
            log.info("[OCR-Phase1] PDF/A SHA-256 : {}", pdfaSha256);

            // ── 6. OCR — guidé par le vocabulaire du type (attributs + valeurs
            //      déjà confirmées sur des documents précédents du même type) ─
            // Conserve aussi la position de chaque mot reconnu (voir
            // OcrPositionalExtractionService) : la regex apprise sur un document
            // précédent reste utile, mais chercher directement le NOM de chaque
            // champ comme libellé sur CE document fonctionne dès le premier
            // essai, et n'est pas affecté par une mise en page en colonnes qui
            // aurait éloigné un libellé de sa valeur dans le texte linéaire.
            OcrService.OcrExtractionResult extraction = ocrService.extractWithPositions(pdfABytes, typeDocument);
            String extractedText = extraction.texte();
            log.info("[OCR-Phase1] Texte : {} caractères, {} mot(s) positionné(s)",
                     extractedText != null ? extractedText.length() : 0, extraction.mots().size());

            // ── 8. Suggestions — positionnelles (ce document) + regex (héritées) ─
            // Le positionnel prime quand les deux trouvent quelque chose : ancré
            // sur CE document précis, il est généralement plus fiable qu'une
            // regex apprise sur un autre document du même type.
            boolean regexAlreadyGenerated = typeDocument.hasRegexGenerated();
            Map<String, String> regexMap = typeDocument.getExtractionRegexMap();
            Map<String, String> suggestionsRegex = calculateSuggestions(regexMap, extractedText);
            Map<String, String> suggestionsPositionnelles =
                typeDocument.getMetaData() != null
                    ? ocrPositionalExtractionService.extraire(extraction.mots(), typeDocument.getMetaData())
                    : Map.<String, String>of();

            Map<String, String> suggestions = new LinkedHashMap<>(suggestionsRegex);
            suggestions.putAll(suggestionsPositionnelles);

            if (!suggestionsPositionnelles.isEmpty())
            {
                log.info("[OCR-Phase1] ✅ {} suggestion(s) positionnelle(s) (libellé trouvé sur ce document)",
                         suggestionsPositionnelles.size());
            }

            if (suggestions.isEmpty() && extractedText != null && !extractedText.isBlank())
            {
                if (regexAlreadyGenerated)
                {
                    log.info("[OCR-Phase1] ✅ Regex chargées depuis TypeDocument mais " +
                             "aucune correspondance trouvée dans le texte OCR");
                }
                else
                {
                    log.info("[OCR-Phase1] Aucune suggestion — " +
                             "première utilisation du type (regex non encore générées). " +
                             "La génération regex aura lieu en Phase 2 après saisie des valeurs.");
                }
            }
            else
            {
                log.info("[OCR-Phase1] ✅ {} suggestion(s) au total ({} positionnelle(s), {} via regex existantes)",
                         suggestions.size(), suggestionsPositionnelles.size(), suggestionsRegex.size());
            }

            // ── 9. Construire la session avec les deux hashes ─────────────────
            OcrSessionCache.OcrSessionData sessionData = new OcrSessionCache.OcrSessionData();
            sessionData.typeDocumentId        = typeDocumentId;
            sessionData.pdfABytes             = pdfABytes;
            sessionData.originalBytes         = originalBytes;
            sessionData.originalFilename      = originalFilename;
            sessionData.extractedText         = extractedText;
            sessionData.suggestions           = suggestions;
            sessionData.originalSha256        = originalSha256;
            sessionData.pdfaSha256            = pdfaSha256;
            sessionData.regexAlreadyGenerated = regexAlreadyGenerated;

            return sessionData;
        }
        catch (BusinessException | PdfAConversionException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[OCR-Phase1] ❌ Erreur : {}", e.getMessage(), e);
            throw new BusinessException(
                "Erreur OCR preview : " + e.getMessage(), e);
        }
    }

    
    private Map<String, String> calculateSuggestions(
        Map<String, String> regexMap,
        String extractedText)
    {
        Map<String, String> suggestions = new LinkedHashMap<>();

        if (extractedText == null || extractedText.isBlank())
        {
            log.debug("[OCR-Suggestions] Aucun texte extractible");
            return suggestions;
        }

        // ✅ Itérer sur la Map de regex au lieu de List<MetaData>
        for (Map.Entry<String, String> entry : regexMap.entrySet())
        {
            String fieldName = entry.getKey();
            String regex = entry.getValue();

            if (regex == null || regex.isBlank())
            {
                log.debug("[OCR-Suggestions] Regex vide pour '{}' → pas de suggestion",
                         fieldName);
                continue;
            }

            try
            {
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(extractedText);

                if (matcher.find())
                {
                    // Préférer le groupe capturant (1) si présent, sinon match complet
                    String suggestion = matcher.groupCount() > 0
                            ? matcher.group(1)
                            : matcher.group();

                    if (suggestion != null && !suggestion.isBlank())
                    {
                        suggestions.put(fieldName, suggestion);
                        log.debug("[OCR-Suggestions] ✅ '{}' → '{}'",
                                 fieldName, suggestion);
                    }
                }
                else
                {
                    log.debug("[OCR-Suggestions] Regex '{}' : aucune correspondance",
                             fieldName);
                }
            }
            catch (Exception e)
            {
                log.warn("[OCR-Suggestions] ❌ Regex invalide pour '{}' : {} (Erreur: {})",
                         fieldName, regex, e.getMessage());
            }
        }

        return suggestions;
    }
}