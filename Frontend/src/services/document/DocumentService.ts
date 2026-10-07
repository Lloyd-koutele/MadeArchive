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
    /** Activité par défaut du type (plan de classement de l'UO) — null/absent = non classé. */
    planClassementNoeudId?: number | null;
    activite?: string | null;
}

export interface UserDto {
    id:        string;
    nom:       string;
    prenom:    string;
    email:     string;
    // Optionnel : présent sur les réponses UserResponseDto (getAllUsers,
    // getCandidatsGroupe...), absent de certaines autres (ex. MembreDto de
    // GroupeService, qui ne porte que id/nom/prenom/email) — utilisé pour le
    // filtre de recherche des listes de membres (voir GestionGroupe.tsx).
    telephone?: string;
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
    /** Durée de rétention (en années) du TYPE de ce document, null si aucune limite —
     *  voir DocumentDetailDto.retentionYearsType (même usage). */
    retentionYearsType?: number | null;
    /** true si l'utilisateur consultant peut envoyer/restaurer CE document précis vers/depuis la corbeille. */
    peutGererCorbeille?:  boolean;
    /** true si CE document précis, en CORBEILLE, peut être supprimé définitivement
     *  MAINTENANT (délai de grâce écoulé ET sort final CONSERVER/TRIER — voir
     *  supprimerDefinitivementDepuisCorbeille). false pour un document dont le
     *  sort final est DETRUIRE : celui-là sera purgé automatiquement. */
    peutSupprimerDefinitivement?: boolean;
    /** ERREUR_ARCHIVAGE | SUPPRESSION_LEGALE | AUTRE | FIN_DE_VIE — null = non renseigné (ancienne suppression). */
    motifSuppression?: string | null;
    commentaireSuppression?: string | null;
    /** true si le SYSTÈME supprimera ce document seul à l'échéance ; faux = conservation permanente, décision de l'éditeur. */
    suppressionAutomatique?: boolean;
    eliminationBloquee?: boolean;
    blocageMotif?: string | null;
    peutBloquerElimination?: boolean;
    peutDebloquerElimination?: boolean;
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
    /** Durée de rétention (en années) du TYPE de ce document, null si aucune limite —
     *  utilisée pour annoncer la nouvelle échéance avant de renouveler la rétention
     *  d'un document restauré dont retentionUntil est déjà dépassé (voir
     *  restaurerDocumentDepuisCorbeille). */
    retentionYearsType: number | null;
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
    /** Dossier auquel ce document est rattaché, s'il y en a un. Null sinon. */
    dossierId: number | null;
    dossierNom: string | null;
    /** Fil d'Ariane complet jusqu'à ce dossier, ex. "DGE / M1" — racine en
     *  premier. Null si pas de dossier (voir DossierService.construireChemin côté serveur). */
    dossierCheminComplet: string | null;
    /** true si l'utilisateur consultant peut rattacher/migrer/détacher ce document d'un dossier. */
    peutModifierDossier: boolean;
    /** true si l'utilisateur consultant peut basculer PUBLIC ↔ PRIVÉ ce document
     *  (toujours false si le document hérite de la confidentialité d'un dossier PRIVÉ). */
    peutModifierAcces: boolean;
    /** Activité EFFECTIVE (plan de classement de l'UO), ex. "03 Finances › 03.2 Factures" : celle de ce document
     *  si elle a été précisée, sinon celle de son type. Null = non classé. */
    activite?: string | null;
    /** Id du nœud de l'activité effective — null = non classé. */
    activiteNoeudId?: number | null;
    /** true = activité précisée pour CE document (exception) ; false = héritée de son type. */
    activiteSurDocument?: boolean;
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
 * GET /api/user/docs/par-type/{typeId}?page=&size=&dateDebut=&dateFin=
 * Documents d'un type, paginés depuis la BD.
 * dateDebut/dateFin optionnels (ISO yyyy-MM-dd) — filtre sur la date d'archivage.
 */
export const getMesDocumentsByType = async (
    typeId: number,
    page   = 1,
    size   = 10,
    dateDebut?: string,
    dateFin?:   string,
): Promise<DocumentPageDto> => {
    try {
        const response = await api.get(`/user/docs/par-type/${typeId}`, {
            params: {
                page, size,
                ...(dateDebut ? { dateDebut } : {}),
                ...(dateFin   ? { dateFin }   : {}),
            },
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
 * GET /api/user/docs/recherche?q=&typeId=&page=&size=&dateDebut=&dateFin=
 * Recherche full-text (Meilisearch → BD).
 * dateDebut/dateFin optionnels (ISO yyyy-MM-dd) — filtre sur la date d'archivage.
 */
export const rechercherDocuments = async (
    q?:     string,
    typeId?: number,
    page   = 1,
    size   = 10,
    dateDebut?: string,
    dateFin?:   string,
): Promise<DocumentPageDto> => {
    try {
        const response = await api.get('/user/docs/recherche', {
            params: {
                ...(q         ? { q }         : {}),
                ...(typeId    ? { typeId }    : {}),
                ...(dateDebut ? { dateDebut } : {}),
                ...(dateFin   ? { dateFin }   : {}),
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
 *
 * `timeoutMs` optionnel — SANS lui, la requête n'a aucune limite de temps
 * (défaut axios), ce qui convient au lecteur PDF plein écran (ouvert
 * explicitement par l'utilisateur, qui voit un état "Chargement..."). Pour
 * les vues en grille, voir getThumbnailBlob() ci-dessous à la place — ce
 * n'est plus le PDF/A entier qui y est chargé.
 */
export const streamPdfAAsBlob = async (id: string, timeoutMs?: number): Promise<string> => {
    const response = await api.get(`/user/docs/${id}/view`, {
        responseType: 'blob',
        ...(timeoutMs ? { timeout: timeoutMs } : {}),
    });
    return URL.createObjectURL(response.data);
};

/**
 * Récupère la miniature JPEG (1re page) d'un document — générée et mise en
 * cache CÔTÉ SERVEUR (voir DocumentService.java#getThumbnail), pas le PDF/A
 * entier. Remplace l'ancien pipeline des vues en grille (télécharger le PDF
 * complet + le rasteriser côté client avec pdf.js, voir PdfThumbnail.ts) —
 * bien trop coûteux en réseau/CPU dès que la grille peut afficher un grand
 * nombre de documents, le but étant de tenir à l'échelle d'un catalogue de
 * plusieurs milliards de documents, pas seulement de quelques dizaines.
 *
 * `timeoutMs` optionnel, voir streamPdfAAsBlob ci-dessus — même raison
 * (éviter un spinner de carte bloqué indéfiniment).
 */
export const getThumbnailBlob = async (id: string, timeoutMs?: number): Promise<string> => {
    const response = await api.get(`/user/docs/${id}/thumbnail`, {
        responseType: 'blob',
        ...(timeoutMs ? { timeout: timeoutMs } : {}),
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
 * seulement un corrompu. Suppression définitive dans 6 jours, restaurable
 * jusque-là (voir restaurerDocumentDepuisCorbeille). Réservé à un éditeur
 * ayant accès au document.
 */
export const envoyerDocumentCorbeille = async (
    id: string, motif: string, commentaire?: string,
): Promise<void> => {
    try {
        await api.post(`/user/docs/${id}/corbeille`, { motif, commentaire });
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * POST /api/user/docs/{id}/restaurer?renouvelerRetention=
 * Restaure un document depuis la corbeille — réservé à un éditeur ayant
 * accès au document. renouvelerRetention : à passer à true UNIQUEMENT après
 * confirmation explicite de l'éditeur quand retentionYearsType (voir
 * DocumentDetailDto) a servi à lui annoncer la nouvelle échéance — le
 * serveur refuse la restauration (erreur métier) si retentionUntil du
 * document est déjà dépassé et que ce n'est pas true, pour ne jamais le
 * renvoyer silencieusement retomber en corbeille au prochain passage du job.
 */
export const restaurerDocumentDepuisCorbeille = async (id: string, renouvelerRetention = false): Promise<void> => {
    try {
        await api.post(`/user/docs/${id}/restaurer`, null, {
            params: renouvelerRetention ? { renouvelerRetention } : {},
        });
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * POST /api/user/docs/{id}/corbeille/supprimer-definitivement
 * Suppression définitive IMMÉDIATE d'un document en CORBEILLE — seule issue
 * pour un document dont le sort final (CONSERVER/TRIER, voir
 * DocumentListItemDto.peutSupprimerDefinitivement) exclut la purge
 * automatique après le délai de grâce : sans cet appel, il resterait en
 * corbeille indéfiniment. Irréversible — à confirmer côté UI avant l'appel.
 */
export const supprimerDefinitivementDepuisCorbeille = async (
    id: string, motif: string, commentaire?: string,
): Promise<void> => {
    try {
        await api.post(`/user/docs/${id}/corbeille/supprimer-definitivement`, { motif, commentaire });
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/** POST /api/user/docs/{id}/corbeille/bloquer — bloque la suppression automatique (motif obligatoire). */
export const bloquerEliminationDocument = async (id: string, motif: string): Promise<void> => {
    try {
        await api.post(`/user/docs/${id}/corbeille/bloquer`, { motif });
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/** POST /api/user/docs/{id}/corbeille/debloquer — rétablit la suppression automatique, délai de grâce complet. */
export const debloquerEliminationDocument = async (id: string, motif: string): Promise<void> => {
    try {
        await api.post(`/user/docs/${id}/corbeille/debloquer`, { motif });
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * true si retentionUntil est déjà passé (ou égal à aujourd'hui) — un
 * document dans ce cas ne peut pas être restauré depuis la corbeille sans
 * confirmer un renouvellement de sa rétention (voir
 * formaterNouvelleEcheanceRetention et restaurerDocumentDepuisCorbeille).
 */
export const retentionEstDepassee = (retentionUntil: string | null | undefined): boolean => {
    if (!retentionUntil) return false;
    return new Date(retentionUntil) <= new Date();
};

/**
 * Calcule la nouvelle échéance de rétention (depuis AUJOURD'HUI, durée du
 * TYPE de document) à annoncer à l'éditeur avant qu'il ne confirme la
 * restauration d'un document dont la rétention est dépassée — purement
 * informatif côté client, le serveur recalcule la même chose de son côté
 * (voir DocumentService.restaurerDepuisCorbeille). null si ce type n'a pas
 * de limite de rétention (le document sera restauré sans limite).
 */
export const formaterNouvelleEcheanceRetention = (
    retentionYearsType: number | null | undefined
): { annees: number; dateAffichee: string } | null => {
    if (retentionYearsType == null) return null;
    const d = new Date();
    d.setFullYear(d.getFullYear() + retentionYearsType);
    return { annees: retentionYearsType, dateAffichee: d.toLocaleDateString('fr-FR') };
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
 * PUT /api/user/docs/{id}/acces
 * Bascule PUBLIC ↔ PRIVÉ après coup — réservé à l'éditeur ayant accès.
 * Refusé si le document hérite de la confidentialité d'un dossier PRIVÉ
 * (voir DocumentService.modifierAcces côté serveur : il faut alors changer
 * l'accès du dossier, pas celui du document). groupeMembresIds n'a d'effet
 * que si access passe à 'PRIVE' (membres initiaux du nouveau groupe, en
 * plus de l'éditeur qui fait la demande).
 */
export const modifierAcces = async (
    id: string, access: 'PUBLIC' | 'PRIVE', groupeMembresIds?: string[]
): Promise<DocumentDetailDto> => {
    try {
        const response = await api.put(`/user/docs/${id}/acces`, { access, groupeMembresIds });
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
 * PUT /api/user/docs/{id}/dossier?dossierId=...&fusionnerGroupes=...
 * Change le dossier du document — omettre dossierId le détache de son dossier
 * actuel ("le faire sortir du dossier") ; le fournir le migre vers ce
 * dossier (qu'il en ait déjà un ou non). Réservé à l'éditeur ayant accès.
 *
 * fusionnerGroupes : à passer à true seulement après avoir appelé
 * verifierFusionGroupeDossier et obtenu la confirmation de l'éditeur si les
 * groupes diffèrent — voir ce dernier.
 */
export const modifierDossierDocument = async (
    id: string, dossierId: number | null, fusionnerGroupes = false
): Promise<DocumentDetailDto> => {
    try {
        const response = await api.put(`/user/docs/${id}/dossier`, null, {
            params: dossierId ? { dossierId, fusionnerGroupes } : {},
        });
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * GET /api/user/docs/{id}/dossier/{dossierId}/verifier-fusion-groupe
 * À appeler avant modifierDossierDocument quand le document et le dossier
 * cible sont tous les deux privés, pour savoir s'il faut avertir l'éditeur
 * qu'un rattachement fusionnera les deux groupes d'accès.
 */
export interface FusionGroupeCheckDto {
    groupesDifferents: boolean;
    membresQuiSerontAjoutes: string[];
}

export const verifierFusionGroupeDossier = async (
    documentId: string, dossierId: number
): Promise<FusionGroupeCheckDto> => {
    try {
        const response = await api.get(`/user/docs/${documentId}/dossier/${dossierId}/verifier-fusion-groupe`);
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
    /** Rattache le document à un dossier (dossier/affaire) existant. */
    dossierId?: number;
    /** Ce document devient la version suivante de ce document existant. */
    documentPrecedentId?: string;
    /** Emplacement physique de l'original papier, s'il y en a un (optionnel). */
    physicalLocationId?: string;
    /** Activité de ce document (ou de tout le lot) si elle diffère de celle de son type — omis = suit son type. */
    planClassementNoeudId?: number;
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
    /** Avertissements de contrôle du type de fichier (jamais bloquants) — ex. .doc au lieu de .docx, fichier sans extension. */
    avertissements?: string[];
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

/**
 * Absent (undefined) si aucun document similaire trouvé, OU si l'utilisateur
 * n'y a pas accès — jamais construit côté serveur dans ce cas (voir
 * DocumentSimilaireDto backend), donc jamais l'indice qu'un document existe
 * pour quelqu'un qui n'y a pas droit.
 */
export interface DocumentSimilaireDto {
    documentId: string;
    titre: string;
}

export interface OcrPreviewItemDto {
    sessionId:           string | null;
    /** Nom du fichier traité — notamment utile pour l'import via lien (pas de File[] côté client). */
    nomFichier?:         string;
    metaDataSuggestions: Record<string, string> | null;
    message?:            string;
    /** Avertissement, jamais un blocage — voir Document.texteNormaliseSha256 côté backend. */
    documentSimilaire?:  DocumentSimilaireDto;
    /** Absent si non pertinent (pas un tableur mis à l'échelle) ou mesure
     *  échouée. Sinon, plus petite taille de police (pt) trouvée dans le PDF
     *  converti — voir DocumentOcrService.mesurerPoliceMinimalePt côté serveur. */
    policeMinPt?:        number;
    /** Avertissements du contrôle du type réel du fichier : l'éditeur assume l'archivage tel quel. */
    avertissements?:     string[];
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
 * Récupère les utilisateurs de l'UO donnée. Anciennement /api/editor/users
 * (sans scope d'UO) : cette route n'a jamais existé côté backend.
 * Volontairement PAS utilisé pour le choix des membres d'un groupe d'accès
 * (voir getCandidatsGroupe ci-dessous) : ne renvoie que les collègues de
 * l'UO, jamais les ADMIN globaux.
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

/**
 * GET /api/editor/uo/{uoId}/candidats-groupe
 * Utilisateurs proposables comme membres d'un groupe d'accès (document ou
 * dossier privé) À LA CRÉATION — collègues de l'UO donnée, PLUS tous les
 * ADMIN globaux (même règle que GroupeService.getDisponibles /
 * DossierGroupeService.getDisponiblesDossier, utilisés eux APRÈS la création
 * une fois le document/dossier et son groupe déjà créés).
 */
export const getCandidatsGroupe = async (uoId: number): Promise<UserDto[]> => {
    try {
        const response = await api.get(`/editor/uo/${uoId}/candidats-groupe`);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data ?? error.message ?? 'Erreur récupération des candidats au groupe',
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
    /** Restreint aux documents rattachés à un dossier précis (voir DossiersPanel). */
    dossierId?:       number | null;
    /** Restreint aux documents dont le type est rattaché à cette activité du plan de classement OU à l'une de ses sous-activités. */
    planClassementNoeudId?: number | null;
    /** Restreint aux documents rattachés à un emplacement physique précis (voir PhysicalLocationsPanel). */
    physicalLocationId?: string | null;
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
                ...(params.dossierId       ? { dossierId:       params.dossierId }                 : {}),
                ...(params.planClassementNoeudId ? { planClassementNoeudId: params.planClassementNoeudId } : {}),
                ...(params.physicalLocationId ? { physicalLocationId: params.physicalLocationId }   : {}),
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

// ═══════════════════════════════════════════════════════════════════════════
// JOURNAL DE CYCLE DE VIE D'UN DOCUMENT (/api/user/docs/{id}/journal)
// ═══════════════════════════════════════════════════════════════════════════

/** Une entrée du journal — même forme que le journal d'audit (made.archive.dto.AuditLogDto). */
export interface DocumentJournalEntreeDto {
    id: number;
    horodatage: string;
    acteurEmail: string | null;
    acteurRole: string | null;
    /** Renseignée seulement pour un ADMIN/ADMIN_UO — null pour les autres. */
    adresseIp: string | null;
    action: string;
    description: string;
    succes: boolean;
    details: string | null;
}

export interface DocumentJournalDto {
    content: DocumentJournalEntreeDto[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    /** Dernier contrôle d'intégrité : seuls les ÉCHECS sont journalisés un par un. */
    dernierControleLe: string | null;
    dernierControleResultat: string | null;
}

/** GET /api/user/docs/{id}/journal — historique du document, plus récent d'abord. */
export const getJournalDocument = async (id: string, page = 0, size = 25): Promise<DocumentJournalDto> => {
    try {
        return (await api.get(`/user/docs/${id}/journal`, { params: { page, size } })).data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/** GET /api/user/docs/{id}/journal/export — télécharge le journal entier (CSV ou .log). */
export const exporterJournalDocument = async (id: string, format: 'csv' | 'log'): Promise<void> => {
    try {
        const response = await api.get(`/user/docs/${id}/journal/export`, { params: { format }, responseType: 'blob' });
        triggerDownload(response.data, `journal-document_${id}.${format}`);
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

// ═══════════════════════════════════════════════════════════════════════════
// RECLASSEMENT (/api/user/docs/{id}/reclasser)
// ═══════════════════════════════════════════════════════════════════════════

export interface ReclassementRequest {
    /** Nouveau type (même UO) — omis ou identique à l'actuel : le type ne change pas. */
    typeDocumentId?: number;
    /** Valeurs des métadonnées du NOUVEAU type, par libellé de champ. */
    metaData?: { nom: string; valeur: string }[];
    /** true = appliquer planClassementNoeudId (null = revenir à l'activité par défaut du type). */
    modifierActivite?: boolean;
    planClassementNoeudId?: number | null;
    modifierDossier?: boolean;
    dossierId?: number | null;
    fusionnerGroupes?: boolean;
    modifierEmplacement?: boolean;
    physicalLocationId?: string | null;
}

/**
 * PUT /api/user/docs/{id}/reclasser — corrige le classement d'un document archivé par erreur (type +
 * métadonnées, dossier, emplacement) SANS le supprimer ni le réarchiver : le fichier, ses empreintes, sa
 * signature et son horodatage ne changent pas. Ne concerne que la version ouverte.
 */
export const reclasserDocument = async (id: string, requete: ReclassementRequest): Promise<DocumentDetailDto> => {
    try {
        return (await api.put(`/user/docs/${id}/reclasser`, requete)).data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};
