package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.dto.AttestationDto;
import made.archive.dto.DocumentAccessFilterDto;
import made.archive.dto.DocumentDetailDto;
import made.archive.dto.DocumentFolderDto;
import made.archive.dto.DocumentPageDto;
import made.archive.service.document.AttestationService;
import made.archive.service.document.DocumentAccessService;
import made.archive.exception.BusinessException;
import made.archive.service.document.DocumentService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Contrôleur unique pour la consultation des documents.
 *
 * Base : /api/user
 *
 * Endpoints :
 *
 *   GET  /api/user/docs/folders               (ROLE_EDITOR — "mes documents")
 *        → grille des types (dossiers) avec compteur de documents QUE J'AI UPLOADÉS
 *
 *   GET  /api/user/docs/par-type/{typeId}?page=&size=   (ROLE_EDITOR — "mes documents")
 *        → documents d'un type, paginés depuis la BD, QUE J'AI UPLOADÉS
 *
 *   GET  /api/user/docs/recherche?q=&typeId=&page=&size=   (ROLE_EDITOR — "mes documents")
 *        → recherche hybride Meilisearch (IDs) + BD (données), QUE J'AI UPLOADÉS
 *
 *   GET  /api/user/docs/{id}                   (ROLE_USER)
 *        → détail complet d'un document avec ses métadonnées — pas seulement
 *          les miens : voir DocumentService.resolveDocument/estVisibleNormalement
 *
 *   GET  /api/user/docs/{id}/view              (ROLE_USER)
 *        → streame le PDF/A inline pour le lecteur PDF
 *
 *   GET  /api/user/docs/{id}/download/pdfa     (ROLE_USER)
 *        → télécharge le PDF/A (Content-Disposition: attachment)
 *
 *   POST /api/user/docs/{id}/attestation       (ROLE_USER)
 *        → génère/récupère l'attestation d'archivage (jeton public, voir
 *          AttestationService) — mêmes règles d'accès que le view/download
 *          ci-dessus, le PDF public lui-même est servi sans authentification
 *          par AttestationPublicController (/api/public/attestation/**)
 *
 * Les trois premiers endpoints restent réservés à ROLE_EDITOR et scopés à MES
 * propres documents (gestion de mes uploads) — les trois derniers sont ouverts
 * à tout ROLE_USER et donnent accès à n'importe quel document auquel je suis
 * normalement autorisé (public de mon UO, ou membre de son groupe privé),
 * pas seulement ceux que j'ai moi-même déposés. L'identité de l'utilisateur
 * est toujours résolue depuis UserDetails (Spring Security), jamais depuis un
 * paramètre client.
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserDocumentController
{
    private final DocumentService documentService;
    private final DocumentAccessService documentAccessService;
    private final AttestationService attestationService;

    // ═══════════════════════════════════════════════════════════════════
    // Grille de dossiers
    // ═══════════════════════════════════════════════════════════════════

    /**
     * GET /api/user/docs/folders
     *
     * Retourne la liste des types de documents utilisés par l'éditeur connecté,
     * avec le nombre de documents par type.
     * Limité à 10 types par défaut (les plus récents).
     * Le filtrage par nom est fait côté client sur cette liste.
     */
    @Secured("ROLE_EDITOR")
    @GetMapping("/docs/folders")
    public ResponseEntity<?> getMesFolders(
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            List<DocumentFolderDto> folders = documentService.getMesFolders(userDetails);
            return ResponseEntity.ok(folders);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest()
                .body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError("INTERNAL_ERROR",
                    "Erreur récupération dossiers : " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Liste paginée par type
    // ═══════════════════════════════════════════════════════════════════

    /**
     * GET /api/user/docs/par-type/{typeId}?page=1&size=10
     *
     * Retourne les documents de l'éditeur pour un type donné.
     * Source : BD uniquement. Tri par date de création décroissante.
     * Exclut les documents DELETED.
     */
    @Secured("ROLE_EDITOR")
    @GetMapping("/docs/par-type/{typeId}")
    public ResponseEntity<?> getMesDocumentsByType(
        @PathVariable Long typeId,
        @RequestParam(defaultValue = "1")  int page,
        @RequestParam(defaultValue = "10") int size,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            DocumentPageDto result = documentService.getMesDocumentsByType(
                typeId, page, size, userDetails);
            return ResponseEntity.ok(result);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest()
                .body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError("INTERNAL_ERROR",
                    "Erreur récupération documents : " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Recherche hybride
    // ═══════════════════════════════════════════════════════════════════

    /**
     * GET /api/user/docs/recherche?q=dupont&typeId=3&page=1&size=10
     *
     * Recherche full-text via Meilisearch (retourne des IDs),
     * puis charge les données depuis la BD.
     *
     * Si q est vide → liste BD directe (par type si typeId fourni,
     * sinon tous les documents de l'éditeur).
     *
     * typeId est optionnel : si fourni, la recherche est restreinte à ce type.
     */
    @Secured("ROLE_EDITOR")
    @GetMapping("/docs/recherche")
    public ResponseEntity<?> rechercher(
        @RequestParam(required = false) String  q,
        @RequestParam(required = false) Long    typeId,
        @RequestParam(defaultValue = "1")  int page,
        @RequestParam(defaultValue = "10") int size,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            DocumentPageDto result = documentService.rechercher(
                q, typeId, page, size, userDetails);
            return ResponseEntity.ok(result);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest()
                .body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError("INTERNAL_ERROR",
                    "Erreur recherche : " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Détail d'un document
    // ═══════════════════════════════════════════════════════════════════

    /**
     * GET /api/user/docs/{id}
     *
     * Retourne le détail complet d'un document avec toutes ses métadonnées.
     * Ouvert à quiconque a normalement accès à ce document, pas seulement à
     * son uploadeur (voir DocumentService.resolveDocument).
     */
    @Secured("ROLE_USER")
    @GetMapping("/docs/{id}")
    public ResponseEntity<?> getDetail(
        @PathVariable UUID id,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            DocumentDetailDto detail = documentService.getDetail(id, userDetails);
            return ResponseEntity.ok(detail);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest()
                .body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError("INTERNAL_ERROR",
                    "Erreur récupération détail : " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Visualisation inline PDF/A
    // ═══════════════════════════════════════════════════════════════════

    /**
     * GET /api/user/docs/{id}/view
     *
     * Streame le PDF/A avec Content-Disposition: inline.
     * Utilisé par le lecteur PDF côté client (iframe ou react-pdf).
     * Le navigateur affiche le document sans le télécharger.
     *
     * Ouvert à quiconque a normalement accès à ce document, pas seulement à
     * son uploadeur (voir DocumentService.resolveDocument).
     */
    @Secured("ROLE_USER")
    @GetMapping("/docs/{id}/view")
    public ResponseEntity<byte[]> viewPdfA(
        @PathVariable UUID id,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            byte[] bytes = documentService.streamPdfAForView(id, userDetails);

            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.inline()
                        .filename("document.pdf")
                        .build().toString())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length))
                // Autoriser l'affichage dans une iframe same-origin
                .header("X-Frame-Options", "SAMEORIGIN")
                .body(bytes);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Téléchargement PDF/A
    // ═══════════════════════════════════════════════════════════════════

    /**
     * GET /api/user/docs/{id}/download/pdfa
     *
     * Télécharge le PDF/A archivé avec Content-Disposition: attachment.
     * Le navigateur déclenche le téléchargement du fichier.
     */
    @Secured("ROLE_USER")
    @GetMapping("/docs/{id}/download/pdfa")
    public ResponseEntity<byte[]> downloadPdfA(
        @PathVariable UUID id,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            byte[] bytes    = documentService.downloadPdfA(id, userDetails);
            String filename = documentService.getPdfAFilename(id, userDetails);

            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment()
                        .filename(filename)
                        .build().toString())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length))
                .body(bytes);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Attestation d'archivage
    // ═══════════════════════════════════════════════════════════════════

    /**
     * POST /api/user/docs/{id}/attestation
     *
     * Génère (ou récupère, si déjà générée — idempotent) l'attestation
     * d'archivage d'un document : un jeton public donnant accès en lecture
     * seule + téléchargement au PDF/A, sans jamais changer son statut
     * d'accès. Réservé à qui a normalement accès au document (mêmes règles
     * que consulter/télécharger, voir DocumentService.resolveDocument).
     */
    @Secured("ROLE_USER")
    @PostMapping("/docs/{id}/attestation")
    public ResponseEntity<?> genererAttestation(
        @PathVariable UUID id,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            AttestationDto dto = attestationService.genererOuRecuperer(id, userDetails);
            return ResponseEntity.ok(dto);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Localisation physique
    // ═══════════════════════════════════════════════════════════════════

    /**
     * PUT /api/user/docs/{id}/emplacement?physicalLocationId=...
     *
     * Modifie (ou retire, si le paramètre est omis) l'emplacement physique
     * de l'original papier — réservé à l'éditeur ayant accès au document
     * (voir DocumentService.modifierEmplacementPhysique).
     */
    @Secured("ROLE_USER")
    @PutMapping("/docs/{id}/emplacement")
    public ResponseEntity<?> modifierEmplacementPhysique(
        @PathVariable UUID id,
        @RequestParam(required = false) UUID physicalLocationId,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            return ResponseEntity.ok(
                documentService.modifierEmplacementPhysique(id, physicalLocationId, userDetails));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Suppression d'un document corrompu — planifiée, 3 jours de grâce
    // ═══════════════════════════════════════════════════════════════════

    /**
     * POST /api/user/docs/{id}/planifier-suppression
     *
     * Programme la suppression définitive d'un document CORROMPU dans 3 jours.
     * Réservé à l'éditeur ayant déposé le document (voir DocumentService.planifierSuppression) —
     * un admin peut consulter un document corrompu mais pas le supprimer.
     */
    @Secured("ROLE_EDITOR")
    @PostMapping("/docs/{id}/planifier-suppression")
    public ResponseEntity<?> planifierSuppression(
        @PathVariable UUID id,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            documentService.planifierSuppression(id, userDetails);
            return ResponseEntity.ok(java.util.Map.of("message", "Suppression planifiée dans 3 jours"));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest()
                .body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError("INTERNAL_ERROR", "Erreur : " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helper
    // ═══════════════════════════════════════════════════════════════════

    private java.util.Map<String, Object> buildError(String code, String message)
    {
        return java.util.Map.of(
            "error",     code,
            "message",   message,
            "timestamp", System.currentTimeMillis()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // À AJOUTER dans UserDocumentController.java
    //
    // 1. Ajouter DocumentAccessService dans les dépendances du contrôleur :
    //    private final DocumentAccessService documentAccessService;
    //
    // 2. Ajouter l'import :
    //    import made.archive.dto.DocumentAccessFilterDto;
    //    import made.archive.service.document.DocumentAccessService;
    //
    // 3. Coller l'endpoint ci-dessous dans la classe UserDocumentController
    // ─────────────────────────────────────────────────────────────────────────────
    
    /**
     * GET /api/user/docs/accessibles
     *
     * Retourne tous les documents auxquels l'utilisateur connecté a accès :
     *   - Documents PUBLIC → tous
     *   - Documents PRIVÉ → uniquement si membre du groupe
     *
     * Filtres optionnels (paramètres de requête) :
     *   ?titre=     → recherche partielle insensible à la casse
     *   ?typeId=    → filtre par type de document
     *   ?access=    → PUBLIC | PRIVE (absent = les deux)
     *   ?dateDebut= → date d'archivage ≥ (format ISO : 2024-01-15)
     *   ?dateFin=   → date d'archivage ≤ (format ISO : 2024-12-31)
     *   ?statut=    → ACTIVE | PENDING | CORRUPTED | ACTIVE_WARNING
     *   ?uoId=      → restreint à une UO précise (navigation Admin/Admin_UO dans
     *                 l'arbre) — reste borné au périmètre déjà autorisé, ne
     *                 permet jamais d'en sortir (voir DocumentAccessService)
     *   ?page=      → numéro de page (défaut : 1)
     *   ?size=      → taille de page (défaut : 10, max : 50)
     *
     * Exemples :
     *   GET /api/user/docs/accessibles
     *   GET /api/user/docs/accessibles?access=PUBLIC&page=1&size=20
     *   GET /api/user/docs/accessibles?titre=contrat&dateDebut=2024-01-01&dateFin=2024-12-31
     *   GET /api/user/docs/accessibles?typeId=3&access=PRIVE
     *   GET /api/user/docs/accessibles?uoId=7
     */
    @Secured("ROLE_USER")
    @GetMapping("/docs/accessibles")
    public ResponseEntity<?> getDocumentsAccessibles(
        @RequestParam(required = false) String    titre,
        @RequestParam(required = false) Long      typeId,
        @RequestParam(required = false) String    access,
        @RequestParam(required = false) String    dateDebut,
        @RequestParam(required = false) String    dateFin,
        @RequestParam(required = false) String    statut,
        @RequestParam(required = false) Long      uoId,
        @RequestParam(defaultValue = "1")  int   page,
        @RequestParam(defaultValue = "10") int   size,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            // Construire le filtre depuis les paramètres de requête
            DocumentAccessFilterDto filter = new DocumentAccessFilterDto();
            filter.setTitre(titre);
            filter.setTypeDocumentId(typeId);
            filter.setAccess(access);
            filter.setStatut(statut);
            filter.setUoId(uoId);
            filter.setPage(page);
            filter.setSize(size);
    
            // Parser les dates ISO (YYYY-MM-DD) — null si absent ou invalide
            if (dateDebut != null && !dateDebut.isBlank())
            {
                try { filter.setDateDebut(java.time.LocalDate.parse(dateDebut)); }
                catch (Exception e) { /* date invalide ignorée */ }
            }
            if (dateFin != null && !dateFin.isBlank())
            {
                try { filter.setDateFin(java.time.LocalDate.parse(dateFin)); }
                catch (Exception e) { /* date invalide ignorée */ }
            }
    
            DocumentPageDto result = documentAccessService.getDocumentsAccessibles(
                filter, userDetails);
            return ResponseEntity.ok(result);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest()
                .body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError("INTERNAL_ERROR",
                    "Erreur récupération documents : " + e.getMessage()));
        }
    }
}