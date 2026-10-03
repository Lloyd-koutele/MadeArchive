package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.dto.AttestationDto;
import made.archive.dto.ChangerAccesRequestDto;
import made.archive.dto.DataTypeDto;
import made.archive.dto.DocumentAccessFilterDto;
import made.archive.dto.DocumentDetailDto;
import made.archive.dto.DocumentFolderDto;
import made.archive.dto.DocumentPageDto;
import made.archive.dto.TypeDocumentDto;
import made.archive.entite.TypeDocument;
import made.archive.security.UserDetailsImpl;
import made.archive.service.document.AttestationService;
import made.archive.service.document.DocumentAccessService;
import made.archive.exception.BusinessException;
import made.archive.service.document.DocumentService;
import made.archive.service.document.TypeDocumentService;
import made.archive.util.TypeDocumentMapper;
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
 *        → génère une NOUVELLE attestation d'archivage (jeton public
 *          indépendant, voir AttestationService.genererNouvelle — un
 *          document peut en avoir plusieurs actives simultanément) — mêmes
 *          règles d'accès que le view/download ci-dessus, le PDF public
 *          lui-même est servi sans authentification par
 *          AttestationPublicController (/api/public/attestation/**)
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
    private final made.archive.service.document.DocumentJournalService documentJournalService;
    private final DocumentAccessService documentAccessService;
    private final AttestationService attestationService;
    private final TypeDocumentService typeDocumentService;
    private final TypeDocumentMapper typeDocumentMapper;

    // ═══════════════════════════════════════════════════════════════════
    // Types de documents — pour peupler un filtre côté client
    // ═══════════════════════════════════════════════════════════════════

    /**
     * GET /api/user/types-documents?uoId=
     *
     * Types de documents visibles par l'utilisateur connecté — ouvert à
     * ROLE_USER (donc EDITOR/ADMIN_UO/ADMIN qui en héritent aussi),
     * contrairement à /api/admin_uo/types-documents (ROLE_ADMIN seul) et
     * /api/editor/types-documents (ROLE_EDITOR seul, et non filtré par UO).
     * Sert le filtre "Type de document" de "Documents accessibles", utilisé
     * par tous les tableaux de bord.
     *
     * ?uoId= optionnel : restreint à une UO précise (navigation Admin/
     * Admin_UO dans l'arbre) — reste borné au périmètre déjà autorisé pour
     * l'appelant. Sans lui, retourne tout le périmètre visible de
     * l'appelant (sa propre UO pour EDITOR/USER, son sous-arbre pour
     * ADMIN_UO, tout pour ADMIN).
     */
    @Secured("ROLE_USER")
    @GetMapping("/types-documents")
    public ResponseEntity<?> getTypeDocumentsVisibles(
        @RequestParam(required = false) Long uoId,
        @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            List<TypeDocument> typeDocuments =
                typeDocumentService.getTypeDocumentsVisibles(currentUser.getUser(), uoId);
            List<TypeDocumentDto> dtos = typeDocumentMapper.toDtoList(typeDocuments);
            return ResponseEntity.ok(dtos);
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
                    "Erreur récupération types de documents : " + e.getMessage()));
        }
    }

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
     * GET /api/user/docs/par-type/{typeId}?page=1&size=10&dateDebut=&dateFin=
     *
     * Retourne les documents de l'éditeur pour un type donné.
     * Source : BD uniquement. Tri par date de création décroissante.
     * Exclut les documents DELETED. dateDebut/dateFin (ISO yyyy-MM-dd)
     * optionnels — filtre sur la date d'archivage.
     */
    @Secured("ROLE_EDITOR")
    @GetMapping("/docs/par-type/{typeId}")
    public ResponseEntity<?> getMesDocumentsByType(
        @PathVariable Long typeId,
        @RequestParam(defaultValue = "1")  int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(required = false) java.time.LocalDate dateDebut,
        @RequestParam(required = false) java.time.LocalDate dateFin,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            DocumentPageDto result = documentService.getMesDocumentsByType(
                typeId, page, size, dateDebut, dateFin, userDetails);
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
     * GET /api/user/docs/recherche?q=dupont&typeId=3&page=1&size=10&dateDebut=&dateFin=
     *
     * Recherche full-text via Meilisearch (retourne des IDs),
     * puis charge les données depuis la BD.
     *
     * Si q est vide → liste BD directe (par type si typeId fourni,
     * sinon tous les documents de l'éditeur).
     *
     * typeId est optionnel : si fourni, la recherche est restreinte à ce type.
     * dateDebut/dateFin (ISO yyyy-MM-dd) optionnels — voir
     * DocumentService.rechercher pour leur portée exacte selon le chemin.
     */
    @Secured("ROLE_EDITOR")
    @GetMapping("/docs/recherche")
    public ResponseEntity<?> rechercher(
        @RequestParam(required = false) String  q,
        @RequestParam(required = false) Long    typeId,
        @RequestParam(defaultValue = "1")  int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(required = false) java.time.LocalDate dateDebut,
        @RequestParam(required = false) java.time.LocalDate dateFin,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            DocumentPageDto result = documentService.rechercher(
                q, typeId, page, size, dateDebut, dateFin, userDetails);
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
    // Miniature (vues en grille)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * GET /api/user/docs/{id}/thumbnail
     *
     * Miniature JPEG de la 1re page — PAS le PDF/A entier. Générée et mise
     * en cache une seule fois côté serveur (voir DocumentService.getThumbnail)
     * puis simplement relue ensuite : contrairement à /view (utilisé par le
     * lecteur PDF plein écran), ce point d'entrée est fait pour être appelé
     * pour CHAQUE carte d'une grille pouvant en afficher des milliers —
     * jamais un téléchargement + parsing PDF complet à chaque affichage.
     */
    @Secured("ROLE_USER")
    @GetMapping("/docs/{id}/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(
        @PathVariable UUID id,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            byte[] bytes = documentService.getThumbnail(id, userDetails);

            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                // Le contenu d'une miniature ne change jamais une fois générée
                // (clé fixe par document, jamais réécrite après le premier
                // appel) — autant laisser le navigateur la garder en cache le
                // temps de la session au lieu de la re-télécharger à chaque
                // retour sur la même page.
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
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
     * Génère une NOUVELLE attestation d'archivage d'un document : un jeton
     * public donnant accès en lecture seule + téléchargement au PDF/A, sans
     * jamais changer son statut d'accès. Chaque appel crée un jeton distinct
     * et indépendant (pas de réutilisation — un document peut avoir
     * plusieurs attestations actives à la fois, voir
     * AttestationService.genererNouvelle). Réservé à qui a normalement accès
     * au document (mêmes règles que consulter/télécharger, voir
     * DocumentService.resolveDocument).
     */
    @Secured("ROLE_USER")
    @PostMapping("/docs/{id}/attestation")
    public ResponseEntity<?> genererAttestation(
        @PathVariable UUID id,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            AttestationDto dto = attestationService.genererNouvelle(id, userDetails);
            return ResponseEntity.ok(dto);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError("INTERNAL_ERROR",
                    "Erreur lors de la génération de l'attestation : " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Métadonnées — correction après coup
    // ═══════════════════════════════════════════════════════════════════

    /**
     * PUT /api/user/docs/{id}/metadata
     *
     * Remplace les valeurs de métadonnées d'un document — jamais le
     * fichier/titre/type. Réservé à l'éditeur ayant accès au document (voir
     * DocumentService.modifierMetaData). Si ce document est le seul de son
     * type, invalide automatiquement les regex d'extraction OCR de ce type.
     */
    @Secured("ROLE_USER")
    @PutMapping("/docs/{id}/metadata")
    public ResponseEntity<?> modifierMetaData(
        @PathVariable UUID id,
        @RequestBody List<DataTypeDto> nouvellesValeurs,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            return ResponseEntity.ok(
                documentService.modifierMetaData(id, nouvellesValeurs, userDetails));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
    }

    /**
     * PUT /api/user/docs/{id}/acces
     *
     * Bascule PUBLIC ↔ PRIVÉ après coup — réservé à l'éditeur ayant accès au
     * document (voir DocumentService.modifierAcces). Refusé si le document
     * hérite de la confidentialité d'un dossier PRIVÉ (modifiez l'accès du
     * dossier à la place). groupeMembresIds n'a d'effet que si access passe
     * à PRIVE (nouveaux membres du groupe créé, en plus de l'auteur).
     */
    @Secured("ROLE_USER")
    @PutMapping("/docs/{id}/acces")
    public ResponseEntity<?> modifierAcces(
        @PathVariable UUID id,
        @RequestBody ChangerAccesRequestDto dto,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            return ResponseEntity.ok(documentService.modifierAcces(id, dto, userDetails));
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
    // Dossier — rattacher, migrer ou détacher un document après coup
    // ═══════════════════════════════════════════════════════════════════

    /**
     * PUT /api/user/docs/{id}/dossier?dossierId=...&fusionnerGroupes=...
     *
     * Change le dossier d'un document — omettre dossierId le détache de son
     * dossier actuel ("le faire sortir du dossier") ; le fournir le migre
     * vers ce dossier (qu'il en ait déjà un ou non). Réservé à l'éditeur
     * ayant accès au document, et borné à un dossier de la même UO (voir
     * DocumentService.modifierDossierDocument pour le détail des règles).
     *
     * fusionnerGroupes : à ne passer à true qu'après que le client a appelé
     * GET .../verifier-fusion-groupe et obtenu la confirmation de l'éditeur —
     * voir DocumentService.modifierDossierDocument, qui refuse sinon de
     * fusionner silencieusement deux groupes différents.
     */
    @Secured("ROLE_USER")
    @PutMapping("/docs/{id}/dossier")
    public ResponseEntity<?> modifierDossierDocument(
        @PathVariable UUID id,
        @RequestParam(required = false) Long dossierId,
        @RequestParam(defaultValue = "false") boolean fusionnerGroupes,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            return ResponseEntity.ok(
                documentService.modifierDossierDocument(id, dossierId, fusionnerGroupes, userDetails));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
    }

    /**
     * GET /api/user/docs/{id}/dossier/{dossierId}/verifier-fusion-groupe
     *
     * À appeler AVANT modifierDossierDocument quand le document et le dossier
     * cible sont tous les deux privés, pour savoir s'il faut avertir
     * l'éditeur qu'une fusion de groupes aura lieu (voir
     * DocumentService.verifierFusionGroupe). Lecture seule.
     */
    @Secured("ROLE_USER")
    @GetMapping("/docs/{id}/dossier/{dossierId}/verifier-fusion-groupe")
    public ResponseEntity<?> verifierFusionGroupe(
        @PathVariable UUID id,
        @PathVariable Long dossierId,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            return ResponseEntity.ok(documentService.verifierFusionGroupe(id, dossierId, userDetails));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Corbeille — suppression volontaire, délai de grâce unique (voir
    // DocumentService.DELAI_GRACE_CORBEILLE_JOURS), restaurable
    // ═══════════════════════════════════════════════════════════════════

    /**
     * POST /api/user/docs/{id}/corbeille
     *
     * Envoie un document à la corbeille — n'importe quel document, plus
     * seulement un corrompu (voir DocumentService.envoyerCorbeille).
     * Réservé à un éditeur ayant accès au document.
     */
    @Secured("ROLE_EDITOR")
    @PostMapping("/docs/{id}/corbeille")
    public ResponseEntity<?> envoyerCorbeille(
        @PathVariable UUID id,
        @RequestBody made.archive.dto.SuppressionDocumentRequestDto requete,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            java.time.LocalDate echeance = documentService.envoyerCorbeille(
                id, requete.getMotif(), requete.getCommentaire(), userDetails);
            return ResponseEntity.ok(java.util.Map.of("message",
                "Document envoyé à la corbeille, suppression définitive prévue le " + echeance));
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

    /**
     * POST /api/user/docs/{id}/restaurer?renouvelerRetention=
     *
     * Restaure un document depuis la corbeille — voir
     * DocumentService.restaurerDepuisCorbeille. Réservé à un éditeur ayant
     * accès au document (un admin/admin_uo peut consulter la corbeille mais
     * pas restaurer). renouvelerRetention (défaut false) : à passer à true
     * seulement après confirmation explicite de l'éditeur, quand
     * retentionYearsDuType du document (voir DocumentDetailDto) a été utilisé
     * pour lui annoncer la nouvelle échéance — le service renvoie une erreur
     * métier si le retentionUntil du document est dépassé et que ce n'est
     * pas true, plutôt que de restaurer silencieusement un document qui
     * retomberait aussitôt en corbeille au prochain passage du job.
     */
    @Secured("ROLE_EDITOR")
    @PostMapping("/docs/{id}/restaurer")
    public ResponseEntity<?> restaurerDepuisCorbeille(
        @PathVariable UUID id,
        @RequestParam(required = false, defaultValue = "false") boolean renouvelerRetention,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            documentService.restaurerDepuisCorbeille(id, renouvelerRetention, userDetails);
            return ResponseEntity.ok(java.util.Map.of("message", "Document restauré"));
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

    /**
     * POST /api/user/docs/{id}/corbeille/supprimer-definitivement
     *
     * Suppression définitive immédiate — voir DocumentService.supprimerDefinitivementDepuisCorbeille.
     * Seule issue pour un document dont le sort final (CONSERVER/TRIER) exclut
     * la purge automatique après le délai de grâce : sans cette action, un tel
     * document resterait en corbeille indéfiniment. Réservé à un éditeur ayant
     * accès au document, délai de grâce déjà écoulé (sinon erreur métier).
     */
    @Secured("ROLE_EDITOR")
    @PostMapping("/docs/{id}/corbeille/supprimer-definitivement")
    public ResponseEntity<?> supprimerDefinitivementDepuisCorbeille(
        @PathVariable UUID id,
        @RequestBody made.archive.dto.SuppressionDocumentRequestDto requete,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            documentService.supprimerDefinitivementDepuisCorbeille(
                id, requete.getMotif(), requete.getCommentaire(), userDetails);
            return ResponseEntity.ok(java.util.Map.of("message", "Document supprimé définitivement"));
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

    /**
     * POST /api/user/docs/{id}/corbeille/bloquer — bloque la suppression automatique d'un document en
     * corbeille (motif obligatoire) ; /debloquer la rétablit avec un délai de grâce complet.
     */
    @Secured("ROLE_EDITOR")
    @PostMapping("/docs/{id}/corbeille/bloquer")
    public ResponseEntity<?> bloquerElimination(
        @PathVariable UUID id,
        @RequestBody made.archive.dto.MotifRequestDto requete,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            documentService.bloquerEliminationCorbeille(id, requete.getMotif(), userDetails);
            return ResponseEntity.ok(java.util.Map.of("message", "Suppression bloquée"));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest().body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
    }

    @Secured("ROLE_EDITOR")
    @PostMapping("/docs/{id}/corbeille/debloquer")
    public ResponseEntity<?> debloquerElimination(
        @PathVariable UUID id,
        @RequestBody made.archive.dto.MotifRequestDto requete,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            documentService.debloquerEliminationCorbeille(id, requete.getMotif(), userDetails);
            return ResponseEntity.ok(java.util.Map.of("message", "Suppression débloquée, nouveau délai de grâce"));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest().body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
    }

    /**
     * PUT /api/user/docs/{id}/reclasser — corrige le classement d'un document archivé par erreur (type +
     * métadonnées, dossier, emplacement) sans le supprimer — voir DocumentService.reclasser.
     */
    @Secured("ROLE_EDITOR")
    @PutMapping("/docs/{id}/reclasser")
    public ResponseEntity<?> reclasser(
        @PathVariable UUID id,
        @RequestBody made.archive.dto.ReclassementRequestDto requete,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            return ResponseEntity.ok(documentService.reclasser(id, requete, userDetails));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest().body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
        catch (Exception e)
        {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError("INTERNAL_ERROR", "Erreur : " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Journal de cycle de vie d'un document — voir DocumentJournalService
    // ═══════════════════════════════════════════════════════════════════

    /** GET /api/user/docs/{id}/journal?page=&size= — historique du document, plus récent d'abord. */
    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO", "ROLE_EDITOR"})
    @GetMapping("/docs/{id}/journal")
    public ResponseEntity<?> getJournalDocument(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            return ResponseEntity.ok(documentJournalService.consulter(id, page, size, userDetails));
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest().body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
    }

    /** GET /api/user/docs/{id}/journal/export?format=csv|log — même format que l'export du journal d'audit. */
    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO", "ROLE_EDITOR"})
    @GetMapping("/docs/{id}/journal/export")
    public ResponseEntity<?> exporterJournalDocument(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "csv") String format,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            boolean formatLog = "log".equalsIgnoreCase(format);
            List<made.archive.dto.AuditLogDto> entrees =
                documentJournalService.exporter(id, formatLog ? "log" : "csv", userDetails);
            String contenu = formatLog
                ? made.archive.util.AuditLogExportFormatter.versLogTexte(entrees)
                : made.archive.util.AuditLogExportFormatter.versCsv(entrees);
            byte[] octets = contenu.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String nom = "journal-document_" + id + "_" + java.time.LocalDate.now() + (formatLog ? ".log" : ".csv");

            return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(
                    formatLog ? "text/plain; charset=UTF-8" : "text/csv; charset=UTF-8"))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                    org.springframework.http.ContentDisposition.attachment().filename(nom).build().toString())
                .body(octets);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest().body(buildError("BUSINESS_ERROR", e.getMessage()));
        }
    }

    /**
     * GET /api/user/docs/corbeille?page=&size=
     *
     * Liste les documents en corbeille visibles par l'utilisateur connecté —
     * voir DocumentAccessService.getDocumentsCorbeille pour le détail du
     * périmètre (ADMIN : tout ; ADMIN_UO : son UO + descendantes, lecture
     * seule ; ÉDITEUR : ceux auxquels il a normalement accès). Fermé à
     * ROLE_USER simple — la corbeille n'est pas un espace de consultation
     * générale.
     */
    @Secured({"ROLE_EDITOR", "ROLE_ADMIN_UO", "ROLE_ADMIN"})
    @GetMapping("/docs/corbeille")
    public ResponseEntity<?> getDocumentsCorbeille(
        @RequestParam(defaultValue = "1")  int page,
        @RequestParam(defaultValue = "10") int size,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            DocumentPageDto result = documentAccessService.getDocumentsCorbeille(page, size, userDetails);
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
                    "Erreur récupération de la corbeille : " + e.getMessage()));
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
     *   ?dossierId=  → restreint aux documents rattachés à un dossier précis
     *                 (onglet "Types de documents" d'un dossier, une fois un
     *                 type ouvert) — se combine avec typeId, jamais un
     *                 contournement de la visibilité PUBLIC/PRIVÉ
     *   ?page=      → numéro de page (défaut : 1)
     *   ?size=      → taille de page (défaut : 10, max : 50)
     *
     * Exemples :
     *   GET /api/user/docs/accessibles
     *   GET /api/user/docs/accessibles?access=PUBLIC&page=1&size=20
     *   GET /api/user/docs/accessibles?titre=contrat&dateDebut=2024-01-01&dateFin=2024-12-31
     *   GET /api/user/docs/accessibles?typeId=3&access=PRIVE
     *   GET /api/user/docs/accessibles?uoId=7
     *   GET /api/user/docs/accessibles?dossierId=12&typeId=3
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
        @RequestParam(required = false) Long      dossierId,
        @RequestParam(required = false) Long      planClassementNoeudId,
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
            filter.setDossierId(dossierId);
            filter.setPlanClassementNoeudId(planClassementNoeudId);
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