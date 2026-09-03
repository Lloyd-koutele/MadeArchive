import api from "../api";

// ═══════════════════════════════════════════════════════════════════════════
// TYPES & ENUMS
// ═══════════════════════════════════════════════════════════════════════════

export type TypeAccess     = 'PUBLIC' | 'PRIVE';
export type IntegrityLevel = 'STANDARD' | 'BLOCKCHAIN';
export type DocumentStatus = 'PENDING' | 'ACTIVE' | 'ACTIVE_WARNING' | 'CORRUPTED' | 'DELETED';
export type MetaDataType   = 'CHAR' | 'STRING' | 'INTEGER' | 'FLOAT' | 'DOUBLE' | 'BOOLEAN' | 'DATE' | 'TEXT';

// ═══════════════════════════════════════════════════════════════════════════
// DTOs COMMUNS
// ═══════════════════════════════════════════════════════════════════════════

export interface MetaDataDto {
    id?: number;
    nom: string;
    obligatoire: boolean;
    metaDataType: MetaDataType;
}

export interface MetaDataValueDto {
    nom: string;
    valeur: string;
    typeValeur: MetaDataType;
}

export interface TypeDocumentDto {
    id?: number;
    nom: string;
    metaData: MetaDataDto[];
    userId: string;
    retentionYears: number;
    periodGrace: number;
}

export interface UserDto {
    id:     string;
    nom:    string;
    prenom: string;
    email:  string;
}

// ═══════════════════════════════════════════════════════════════════════════
// LECTURE & CONSULTATION (/api/user/docs/*)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Grille de dossiers (types) avec compteurs.
 */
export interface DocumentFolderDto {
    typeDocumentId:  number;
    typeDocumentNom: string;
    count:           number;
}

/**
 * Item dans une liste paginée.
 */
export interface DocumentListItemDto {
    documentId:      string;
    titre:           string;
    typeDocumentId:  number;
    typeDocumentNom: string;
    status:          string;
    access:          string;
    retentionUntil:  string | null;
    createAt:        string | null;
    /** "Version 1", "Version 2"... ou "Final". null/undefined si jamais versionné (pas de badge). */
    versionLabel?:   string | null;
    /** Statut d'avant corbeille (ex. "CORRUPTED") — non-null uniquement quand status === "CORBEILLE". */
    statutAvantCorbeille?: string | null;
    /** Date de purge définitive prévue — non-null uniquement quand status === "CORBEILLE". */
    suppressionPrevueLe?:  string | null;
    /** true si l'utilisateur consultant peut envoyer/restaurer CE document précis vers/depuis la corbeille. */
    peutGererCorbeille?:  boolean;
}

/**
 * Réponse paginée.
 */
export interface DocumentPageDto {
    content:       DocumentListItemDto[];
    page:          number;
    size:          number;
    totalElements: number;
    totalPages:    number;
}

export interface MetaDataValueInDocDto {
    typeValeur: string | null;
    valeur:     string | null;
}

/**
 * Un maillon de l'historique de versions d'un document.
 */
export interface DocumentVersionDto {
    documentId:         string;
    titre:              string;
    version:            number;
    versionLabel:        string | null;
    estVersionActuelle: boolean;
    createAt:           string | null;
    uploadedByNom:       string | null;
}

/**
 * Détail complet d'un document avec métadonnées et hashes.
 */
export interface DocumentDetailDto {
    documentId:      string;
    titre:           string;
    typeDocumentId:  number;
    typeDocumentNom: string;
    status:          string;
    access:          string;
    integrityLevel:  string | null;
    pdfaSha256:      string | null;
    originalSha256:  string | null;
    retentionUntil:  string | null;
    createAt:        string | null;
    version:         number;
    /** "Version 1", "Version 2"... ou "Final". null si jamais versionné (pas de badge). */
    versionLabel:     string | null;
    /** Chaîne complète (v1 → ... → Final), y compris ce document. Vide si jamais versionné. */
    historiqueVersions: DocumentVersionDto[];
    /** Ce qui a déclenché status === 'CORRUPTED' (hash différent, échec déchiffrement...). Null sinon. */
    corruptionRaison: string | null;
    /** Statut d'avant corbeille (ex. "CORRUPTED") — non-null uniquement quand status === "CORBEILLE". */
    statutAvantCorbeille: string | null;
    /** Date de suppression définitive programmée — non-null uniquement quand status === "CORBEILLE". */
    suppressionPrevueLe: string | null;
    /** true si l'utilisateur consultant peut envoyer ce document à la corbeille, ou le restaurer s'il y est déjà. */
    peutGererCorbeille: boolean;
    metaData:        MetaDataValueInDocDto[];
    /** Emplacement physique de l'original papier, s'il y en a un. Null sinon. */
    physicalLocationId: string | null;
    /** Chemin lisible complet, ex. "Bâtiment A › Salle 204 › Boîte B001". Null si pas d'emplacement. */
    physicalLocationPath: string | null;
    /** true si l'utilisateur consultant peut modifier l'emplacement physique. */
    peutModifierEmplacement: boolean;
    /** UO du document — pour lister les emplacements physiques disponibles. */
    uniteOrganisationnelleId: number | null;
    /** Projet auquel ce document est rattaché, s'il y en a un. Null sinon. */
    projetId: number | null;
    projetNom: string | null;
    /** true si l'utilisateur consultant peut rattacher/migrer/détacher ce document d'un projet. */
    peutModifierProjet: boolean;
}

/**
 * Ancien DTO conservé pour compatibilité avec UserDashboard.
 */
export interface SearchResultItemDto {
    documentId:     string;
    titre:          string;
    typeDocument:   string;
    access:         string;
    status:         string;
    retentionUntil: string | null;
    versionLabel?:   string | null;
}

export interface SearchResultDto {
    totalHits:  number;
    page:       number;
    hitsPerPage: number;
    totalPages: number;
    results:    SearchResultItemDto[];
}

// ┌─────────────────────────────────────────────────────────────────────────┐
// │ Lecture : Dossiers et Listes                                            │
// └─────────────────────────────────────────────────────────────────────────┘

/**
 * GET /api/user/docs/folders
 * Retourne les types utilisés par l'utilisateur connecté avec compteurs.
 */
export const getMesFolders = async (): Promise<DocumentFolderDto[]> => {
    try {
        const response = await api.get('/user/docs/folders');
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur chargement dossiers'
        );
    }
};

/**
 * GET /api/user/docs/par-type/{typeId}?page=&size=
 * Documents d'un type, paginés depuis la BD.
 */
export const getMesDocumentsByType = async (
    typeId: number,
    page   = 1,
    size   = 10,
): Promise<DocumentPageDto> => {
    try {
        const response = await api.get(`/user/docs/par-type/${typeId}`, {
            params: { page, size },
        });
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur chargement documents'
        );
    }
};

// ┌─────────────────────────────────────────────────────────────────────────┐
// │ Lecture : Recherche Hybride                                             │
// └─────────────────────────────────────────────────────────────────────────┘

/**
 * GET /api/user/docs/recherche?q=&typeId=&page=&size=
 * Recherche full-text (Meilisearch → BD).
 */
export const rechercherDocuments = async (
    q?:     string,
    typeId?: number,
    page   = 1,
    size   = 10,
): Promise<DocumentPageDto> => {
    try {
        const response = await api.get('/user/docs/recherche', {
            params: {
                ...(q      ? { q }      : {}),
                ...(typeId ? { typeId } : {}),
                page,
                size,
            },
        });
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur recherche'
        );
    }
};

/**
 * POST /api/user/documents/search (legacy, utilisé par UserDashboard)
 * Compatibilité ancienne API search.
 */
export const searchDocuments = async (params: {
    query:           string;
    typeDocumentId?: number | null;
    page:            number;
    hitsPerPage:     number;
}): Promise<SearchResultDto> => {
    try {
        const response = await api.post('/user/documents/search', {
            query:          params.query,
            typeDocumentId: params.typeDocumentId ?? null,
            page:           params.page,
            hitsPerPage:    params.hitsPerPage,
        });
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur recherche'
        );
    }
};

// ┌─────────────────────────────────────────────────────────────────────────┐
// │ Lecture : Détail & Métadonnées                                          │
// └─────────────────────────────────────────────────────────────────────────┘

/**
 * GET /api/user/docs/{id}
 * Détail complet d'un document avec ses métadonnées.
 */
export const getDocumentDetail = async (id: string): Promise<DocumentDetailDto> => {
    try {
        const response = await api.get(`/user/docs/${id}`);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur chargement détail'
        );
    }
};

// ┌─────────────────────────────────────────────────────────────────────────┐
// │ Lecture : Téléchargement & Streaming                                    │
// └─────────────────────────────────────────────────────────────────────────┘

/**
 * Retourne l'URL pour afficher le PDF/A inline dans un <iframe>.
 * L'authentification est portée par le cookie de session ou le header
 * Authorization — si l'API utilise JWT en header, utilise plutôt
 * streamPdfAAsBlob() ci-dessous.
 */
export const getPdfAViewUrl = (id: string): string =>
    `/api/user/docs/${id}/view`;

/**
 * Télécharge le PDF/A via fetch (compatible JWT en header).
 * Retourne un Blob URL utilisable dans un <iframe src=...> ou <a href=...>.
 */
export const streamPdfAAsBlob = async (id: string): Promise<string> => {
    const response = await api.get(`/user/docs/${id}/view`, {
        responseType: 'blob',
    });
    return URL.createObjectURL(response.data);
};

/**
 * Déclenche le téléchargement du PDF/A archivé.
 */
export const downloadPdfA = async (id: string, titre: string): Promise<void> => {
    const response = await api.get(`/user/docs/${id}/download/pdfa`, {
        responseType: 'blob',
    });
    triggerDownload(response.data, `${titre}_pdfa.pdf`);
};

/**
 * POST /api/user/docs/{id}/corbeille
 * Envoie un document à la corbeille — n'importe quel document, plus
 * seulement un corrompu. Suppression définitive dans 3 jours, restaurable
 * jusque-là (voir restaurerDocumentDepuisCorbeille). Réservé à un éditeur
 * ayant accès au document.
 */
export const envoyerDocumentCorbeille = async (id: string): Promise<void> => {
    try {
        await api.post(`/user/docs/${id}/corbeille`);
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * POST /api/user/docs/{id}/restaurer
 * Restaure un document depuis la corbeille — réservé à un éditeur ayant
 * accès au document.
 */
export const restaurerDocumentDepuisCorbeille = async (id: string): Promise<void> => {
    try {
        await api.post(`/user/docs/${id}/restaurer`);
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * GET /api/user/docs/corbeille?page=&size=
 * Liste les documents en corbeille visibles par l'utilisateur connecté —
 * ADMIN : tout ; ADMIN_UO : son UO + descendantes (lecture seule côté UI,
 * l'action restaurer reste refusée par le serveur) ; ÉDITEUR : ceux
 * auxquels il a normalement accès. Fermé à ROLE_USER simple.
 */
export const getDocumentsCorbeille = async (
    page = 1, size = 10
): Promise<DocumentPageDto> => {
    try {
        const response = await api.get('/user/docs/corbeille', { params: { page, size } });
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * PUT /api/user/docs/{id}/metadata
 * Remplace les valeurs de métadonnées d'un document (jamais le fichier, le
 * titre ni le type) — réservé à l'éditeur ayant accès. Si ce document est
 * le seul de son type, invalide automatiquement les regex OCR de ce type.
 */
export const modifierMetaDataDocument = async (
    id: string, valeurs: { nom: string; valeur: string }[]
): Promise<DocumentDetailDto> => {
    try {
        const response = await api.put(`/user/docs/${id}/metadata`, valeurs);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * PUT /api/user/docs/{id}/emplacement?physicalLocationId=...
 * Modifie (ou retire, si physicalLocationId est omis) l'emplacement physique
 * du document — réservé à l'éditeur ayant accès.
 */
export const modifierEmplacementPhysique = async (
    id: string, physicalLocationId: string | null
): Promise<DocumentDetailDto> => {
    try {
        const response = await api.put(`/user/docs/${id}/emplacement`, null, {
            params: physicalLocationId ? { physicalLocationId } : {},
        });
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * PUT /api/user/docs/{id}/projet?projetId=...&fusionnerGroupes=...
 * Change le projet du document — omettre projetId le détache de son projet
 * actuel ("le faire sortir du projet") ; le fournir le migre vers ce
 * projet (qu'il en ait déjà un ou non). Réservé à l'éditeur ayant accès.
 *
 * fusionnerGroupes : à passer à true seulement après avoir appelé
 * verifierFusionGroupeProjet et obtenu la confirmation de l'éditeur si les
 * groupes diffèrent — voir ce dernier.
 */
export const modifierProjetDocument = async (
    id: string, projetId: number | null, fusionnerGroupes = false
): Promise<DocumentDetailDto> => {
    try {
        const response = await api.put(`/user/docs/${id}/projet`, null, {
            params: projetId ? { projetId, fusionnerGroupes } : {},
        });
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * GET /api/user/docs/{id}/projet/{projetId}/verifier-fusion-groupe
 * À appeler avant modifierProjetDocument quand le document et le projet
 * cible sont tous les deux privés, pour savoir s'il faut avertir l'éditeur
 * qu'un rattachement fusionnera les deux groupes d'accès.
 */
export interface FusionGroupeCheckDto {
    groupesDifferents: boolean;
    membresQuiSerontAjoutes: string[];
}

export const verifierFusionGroupeProjet = async (
    documentId: string, projetId: number
): Promise<FusionGroupeCheckDto> => {
    try {
        const response = await api.get(`/user/docs/${documentId}/projet/${projetId}/verifier-fusion-groupe`);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

// ═══════════════════════════════════════════════════════════════════════════
// UPLOAD & CRÉATION (/api/editor/*)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Requête d'upload simple.
 */
export interface DocumentUploadDto {
    titre: string;
    access: TypeAccess;
    typeDocumentId: number;
    uploadedById: string;
    integrityLevel: IntegrityLevel;
    groupeMembresIds?: string[];
    /** Rattache le document à un projet (dossier/affaire) existant. */
    projetId?: number;
    /** Ce document devient la version suivante de ce document existant. */
    documentPrecedentId?: string;
    /** Emplacement physique de l'original papier, s'il y en a un (optionnel). */
    physicalLocationId?: string;
}

/**
 * Résultat après upload.
 * Le fichier original n'est jamais stocké (seul son SHA-256 sert à la
 * détection de doublons) — le seul artefact conservé est le PDF/A.
 */
export interface DocumentUploadResultDto {
    documentId: string;
    status: DocumentStatus;
    originalSha256: string;
    pdfaSha256: string;
    storageKey: string;
    version?: number;
    versionLabel?: string | null;
    metaDataSuggestions: Record<string, string>;
}

// ┌─────────────────────────────────────────────────────────────────────────┐
// │ Upload : PHASE 1 — OCR Preview (unitaire)                              │
// └─────────────────────────────────────────────────────────────────────────┘

export interface OcrPreviewResponseDto {
    sessionId: string;
    metaDataSuggestions: Record<string, string>;
    message?: string;
}

/**
 * POST /api/editor/ocr-preview
 * Phase 1 : envoi du fichier pour OCR preview (pas de persisted en BD).
 */
export const uploadDocumentOcrPreview = async (
    file: File,
    typeDocumentId: number,
): Promise<OcrPreviewResponseDto> => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('typeDocumentId', String(typeDocumentId));
    try {
        const response = await api.post('/editor/ocr-preview', formData, {
            headers: { 'Content-Type': 'multipart/form-data' },
        });
        console.log('[DIAG-OCR] Réponse brute serveur:', JSON.stringify(response.data, null, 2));
        return response.data;
    } catch (error: any) {
        console.error('[DIAG-OCR] Erreur:', error.response?.data ?? error.message);
        throw new Error(
            error.response?.data?.message ?? error.message ?? "Erreur lors de l'OCR",
        );
    }
};

// ┌─────────────────────────────────────────────────────────────────────────┐
// │ Upload : PHASE 2 — Finalize (unitaire)                                 │
// └─────────────────────────────────────────────────────────────────────────┘

export interface FinalizeUploadRequestDto {
    sessionId: string;
    documentUploadDto: DocumentUploadDto;
    metaDataValidated: MetaDataValueDto[];
}

export interface ValidationErrorDetail {
    champ: string;
    message: string;
    typeAttendu?: string;
    valeurFournie?: string;
}

export interface ValidationErrorResponse {
    error: string;
    message: string;
    details?: ValidationErrorDetail[];
    timestamp: number;
}

export const isValidationError = (error: any): error is ValidationErrorResponse =>
    error?.error === 'VALIDATION_ERROR';

export const isSessionExpired = (error: any): boolean =>
    error?.error === 'SESSION_EXPIRED' || error?.message?.includes('Session expirée');

/**
 * POST /api/editor/finalize-upload
 * Phase 2 : finalisation avec validation et persisting en BD.
 */
export const finalizeUploadDocument = async (
    request: FinalizeUploadRequestDto,
): Promise<DocumentUploadResultDto> => {
    try {
        const response = await api.post('/editor/finalize-upload', request);
        return response.data;
    } catch (error: any) {
        const errorData = error.response?.data;
        const formattedError = new Error(
            errorData?.message ?? error.message ?? 'Erreur lors de la finalisation',
        ) as any;
        formattedError.errorCode         = errorData?.error;
        formattedError.details           = errorData?.details;
        formattedError.isValidationError = errorData?.error === 'VALIDATION_ERROR';
        formattedError.isSessionExpired  = errorData?.error === 'SESSION_EXPIRED';
        throw formattedError;
    }
};

// ┌─────────────────────────────────────────────────────────────────────────┐
// │ Upload : BULK Same-Type — OCR Preview + Finalize                       │
// └─────────────────────────────────────────────────────────────────────────┘

export interface OcrPreviewItemDto {
    sessionId:           string | null;
    /** Nom du fichier traité — notamment utile pour l'import via lien (pas de File[] côté client). */
    nomFichier?:         string;
    metaDataSuggestions: Record<string, string> | null;
    message?:            string;
}

export interface BulkOcrPreviewResponseDto {
    total:    number;
    success:  number;
    failed:   number;
    previews: OcrPreviewItemDto[];
}

export interface BulkFinalizeRequestDto {
    requests: FinalizeUploadRequestDto[];
}

/**
 * GET /api/editor/ocr-preview/{sessionId}/pdf
 * Récupère le PDF déjà généré pendant la Phase 1 OCR (aucune conversion
 * supplémentaire) — pour l'aperçu du document à l'écran de validation, à
 * côté des champs de métadonnées. Retourne un Blob URL utilisable dans un
 * <iframe src=...>. Fonctionne quel que soit le format d'origine (Word,
 * Excel, image...) : c'est le PDF déjà uniformisé côté serveur.
 */
export const getOcrPreviewPdfUrl = async (sessionId: string): Promise<string> => {
    const response = await api.get(`/editor/ocr-preview/${sessionId}/pdf`, {
        responseType: 'blob',
    });
    return URL.createObjectURL(response.data);
};

/**
 * POST /api/editor/docs/bulk/same-type/ocr-preview
 * Lance l'OCR Phase 1 sur N fichiers du même type.
 * Retourne un sessionId + suggestions par fichier.
 */
export const bulkSameTypeOcrPreview = async (
    files:          File[],
    typeDocumentId: number,
    uploadedById:   string,
): Promise<BulkOcrPreviewResponseDto> => {
    const formData = new FormData();
    files.forEach(f => formData.append('files', f));
    formData.append('typeDocumentId', String(typeDocumentId));
    formData.append('uploadedById', uploadedById);
    try {
        const response = await api.post(
            '/editor/docs/bulk/same-type/ocr-preview',
            formData,
            { headers: { 'Content-Type': 'multipart/form-data' } },
        );
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? "Erreur OCR bulk",
        );
    }
};

/**
 * POST /api/editor/docs/bulk/same-type/finalize
 * Finalise chaque document avec les métadonnées validées par le client.
 */
export const bulkSameTypeFinalize = async (
    bulkRequest: BulkFinalizeRequestDto,
): Promise<BulkUploadReportDto> => {
    try {
        const response = await api.post(
            '/editor/docs/bulk/same-type/finalize',
            bulkRequest,
        );
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur finalisation bulk',
        );
    }
};

// ┌─────────────────────────────────────────────────────────────────────────┐
// │ Upload : BULK — rapport de finalisation (partagé par toutes les sources) │
// └─────────────────────────────────────────────────────────────────────────┘

export interface BulkUploadItemResultDto {
    nomFichier:   string;
    typeDocument: string;
    status:       'SUCCESS' | 'FAILED';
    documentId?:  string;
    erreur?:      string;
}

export interface BulkUploadReportDto {
    total:   number;
    success: number;
    failed:  number;
    details: BulkUploadItemResultDto[];
}

// ┌─────────────────────────────────────────────────────────────────────────┐
// │ Upload : BULK Same-Type — source distante via lien web                  │
// └─────────────────────────────────────────────────────────────────────────┘

export interface WebImportFileDto {
    nomFichier: string;
    url:        string;
}

export interface WebImportPreviewResponseDto {
    sourceUrl: string;
    // DOSSIER = dossier Google Drive public, récupéré via navigateur headless
    // (voir HeadlessBrowserImportService côté backend) — les "url" de ses
    // fichiers ne sont pas de vraies URLs (identifiant de cache interne), mais
    // s'utilisent exactement pareil côté client : affichées telles quelles,
    // puis renvoyées sans modification à /web/ocr-preview.
    type:      'FICHIER_DIRECT' | 'PAGE_WEB' | 'DOSSIER';
    fichiers:  WebImportFileDto[];
}

/**
 * POST /api/editor/docs/bulk/same-type/web/preview
 * Découvre les fichiers derrière un lien (fichier direct ou page web listant
 * des documents) SANS les télécharger — pour affichage d'une confirmation.
 */
export const previewImportWeb = async (url: string): Promise<WebImportPreviewResponseDto> => {
    try {
        const response = await api.post('/editor/docs/bulk/same-type/web/preview', { url });
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? "Erreur lors de l'analyse du lien",
        );
    }
};

/**
 * POST /api/editor/docs/bulk/same-type/web/ocr-preview
 * Télécharge les fichiers confirmés par l'utilisateur puis lance l'OCR Phase 1
 * sur chacun. Retourne le même BulkOcrPreviewResponseDto que les autres sources.
 */
export const bulkSameTypeOcrPreviewFromWeb = async (
    fichiersUrls:   string[],
    typeDocumentId: number,
    uploadedById:   string,
): Promise<BulkOcrPreviewResponseDto> => {
    try {
        const response = await api.post('/editor/docs/bulk/same-type/web/ocr-preview', {
            fichiersUrls, typeDocumentId, uploadedById,
        });
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? "Erreur import lien web",
        );
    }
};

// ═══════════════════════════════════════════════════════════════════════════
// GESTION DES TYPES & MÉTADONNÉES (/api/editor/*)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * GET /api/editor/types-documents
 * Liste tous les types de documents.
 */
export const getAllTypeDocuments = async (): Promise<TypeDocumentDto[]> => {
    try {
        const response = await api.get('/editor/types-documents');
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * GET /api/editor/types-documents/{id}
 * Récupère un type de document par ID.
 */
export const getTypeDocumentById = async (id: number): Promise<TypeDocumentDto> => {
    try {
        const response = await api.get(`/editor/types-documents/${id}`);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

// ═══════════════════════════════════════════════════════════════════════════
// AUTRES ENDPOINTS (/api/editor/*)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * GET /api/editor/uo/{uoId}/users
 * Récupère les utilisateurs de l'UO donnée (pour le choix des membres de groupe,
 * ou le champ "uploadé par" à l'upload). Anciennement /api/editor/users (sans
 * scope d'UO) : cette route n'a jamais existé côté backend.
 */
export const getAllUsers = async (uoId: number): Promise<UserDto[]> => {
    try {
        const response = await api.get(`/editor/uo/${uoId}/users`);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data ?? error.message ?? 'Erreur récupération utilisateurs',
        );
    }
};

// ═══════════════════════════════════════════════════════════════════════════
// UTILITAIRES INTERNES
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Helper interne : déclenche le téléchargement d'un blob.
 */
function triggerDownload(blob: Blob, filename: string): void {
    const url  = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href     = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

// ─────────────────────────────────────────────────────────────────────────────
// À AJOUTER dans DocumentUserService.ts
// ─────────────────────────────────────────────────────────────────────────────

// Interface du filtre
export interface DocumentAccessFilterParams {
    titre?:          string;
    typeDocumentId?: number;
    access?:         string;       // "PUBLIC" | "PRIVE" | undefined
    dateDebut?:      string;       // "YYYY-MM-DD"
    dateFin?:        string;       // "YYYY-MM-DD"
    statut?:         string;       // "ACTIVE" | "PENDING" | ...
    /** Restreint à une UO précise (navigation Admin/Admin_UO dans l'arbre). */
    uoId?:           number | null;
    /** Restreint aux documents rattachés à un projet précis (voir ProjetsPanel). */
    projetId?:       number | null;
    page?:           number;
    size?:           number;
}

/**
 * GET /api/user/docs/accessibles
 * Retourne tous les documents accessibles à l'utilisateur connecté,
 * avec filtres optionnels.
 */
export const getDocumentsAccessibles = async (
    params: DocumentAccessFilterParams = {}
): Promise<DocumentPageDto> => {
    try {
        const response = await api.get('/user/docs/accessibles', {
            params: {
                ...(params.titre          ? { titre:          params.titre }                   : {}),
                ...(params.typeDocumentId ? { typeId:         params.typeDocumentId }           : {}),
                ...(params.access         ? { access:         params.access }                   : {}),
                ...(params.dateDebut      ? { dateDebut:      params.dateDebut }                : {}),
                ...(params.dateFin        ? { dateFin:        params.dateFin }                  : {}),
                ...(params.statut         ? { statut:         params.statut }                   : {}),
                ...(params.uoId           ? { uoId:           params.uoId }                     : {}),
                ...(params.projetId       ? { projetId:       params.projetId }                 : {}),
                page: params.page ?? 1,
                size: params.size ?? 10,
            },
        });
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur chargement documents'
        );
    }
};