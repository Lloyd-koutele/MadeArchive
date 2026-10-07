package made.archive.service.document;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.DocumentSimilaireDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache en mémoire pour stocker les données OCR temporaires.
 * Chaque session a une durée de vie de 30 minutes.
 *
 * Trois hashes sont stockés :
 *   originalSha256        → calculé sur le fichier source brut
 *                           utilisé pour le BLOCAGE de doublons (Phase 1)
 *   pdfaSha256            → calculé sur le PDF/A-3b converti
 *                           utilisé pour la vérification d'intégrité (stocké en BDD)
 *   texteNormaliseSha256  → calculé sur le texte OCR normalisé
 *                           utilisé pour l'AVERTISSEMENT de document similaire
 *                           (jamais un blocage, voir Document.texteNormaliseSha256)
 */
@Slf4j
@Service
public class OcrSessionCache
{
    private static final long SESSION_TIMEOUT_MINUTES = 30;
    private final Map<UUID, OcrSessionData> sessions = new ConcurrentHashMap<>();

    @Data
    public static class OcrSessionData
    {
        public UUID   sessionId;
        public Long   typeDocumentId;

        // ── Fichiers ────────────────────────────────────────────────────────
        public byte[] originalBytes;      // fichier source brut
        public byte[] pdfABytes;          // PDF/A-3b converti
        public String originalFilename;

        // ── Hashes ──────────────────────────────────────────────────────────
        /**
         * SHA-256 du fichier ORIGINAL (avant conversion).
         * Sert à détecter les doublons en Phase 1 et Phase 2.
         * Déterministe : le même fichier source → toujours le même hash.
         */
        public String originalSha256;

        /**
         * SHA-256 du PDF/A-3b (après conversion).
         * Sert à vérifier l'intégrité du fichier archivé en MinIO.
         * Non déterministe entre deux conversions (PDFBox injecte des timestamps)
         * mais stable une fois archivé.
         */
        public String pdfaSha256;

        /**
         * SHA-256 du texte OCR normalisé — voir Document.texteNormaliseSha256.
         * Null si aucun texte exploitable n'a été extrait.
         */
        public String texteNormaliseSha256;

        /** Null si aucun document similaire trouvé, ou si non visible par l'uploadeur. */
        public DocumentSimilaireDto documentSimilaire;

        /** Avertissements du contrôle de type (ex. .doc nommé .docx) — montrés à l'éditeur, jamais bloquants. */
        public java.util.List<String> avertissements = java.util.List.of();

        /**
         * Plus petite taille de police (points) mesurée dans le PDF converti —
         * null si non pertinent (pas un tableur, voir
         * LibreOfficeConversionService.ConversionResult.singlePageSheetsApplied)
         * ou si la mesure a échoué. Purement informatif, jamais un blocage —
         * voir DocumentOcrService.mesurerPoliceMinimalePt.
         */
        public Double policeMinPt;

        // ── OCR ─────────────────────────────────────────────────────────────
        public String              extractedText;
        public Map<String, String> suggestions;

        /**
         * true si TypeDocument avait déjà des regex générées au moment du
         * calcul des suggestions. Permet de distinguer, côté message Phase 1,
         * "premier document de ce type — pas encore de règle d'extraction"
         * (normal, informatif) de "règles existantes mais rien trouvé dans ce
         * document" (potentiellement à vérifier).
         */
        public boolean regexAlreadyGenerated;

        // ── Session ──────────────────────────────────────────────────────────
        public Instant createdAt;
    }

    public UUID storeSession(OcrSessionData data)
    {
        UUID sessionId = UUID.randomUUID();
        data.sessionId = sessionId;
        data.createdAt = Instant.now();
        sessions.put(sessionId, data);
        log.info("[OcrCache] Session créée : {} (type: {}, originalSha256: {}...)",
                 sessionId, data.typeDocumentId,
                 data.originalSha256 != null ? data.originalSha256.substring(0, 8) : "null");
        return sessionId;
    }

    public OcrSessionData getSession(UUID sessionId)
    {
        OcrSessionData data = sessions.get(sessionId);
        if (data == null)
        {
            log.warn("[OcrCache] Session inexistante : {}", sessionId);
            return null;
        }

        Instant expiryTime = data.createdAt.plusSeconds(SESSION_TIMEOUT_MINUTES * 60);
        if (Instant.now().isAfter(expiryTime))
        {
            log.warn("[OcrCache] Session expirée : {}", sessionId);
            sessions.remove(sessionId);
            return null;
        }

        return data;
    }

    public void deleteSession(UUID sessionId)
    {
        if (sessions.remove(sessionId) != null)
        {
            log.info("[OcrCache] Session supprimée : {}", sessionId);
        }
    }

    public void cleanup()
    {
        Instant now = Instant.now();
        long removed = sessions.entrySet().stream()
            .filter(e -> now.isAfter(
                e.getValue().createdAt.plusSeconds(SESSION_TIMEOUT_MINUTES * 60)))
            .count();

        sessions.entrySet().removeIf(e ->
            now.isAfter(e.getValue().createdAt
                .plusSeconds(SESSION_TIMEOUT_MINUTES * 60)));

        if (removed > 0)
            log.info("[OcrCache] {} sessions expirées nettoyées", removed);
    }
}