// services/document/TypedocumentService.ts
import api from "../api";

export interface MetaDataDto {
    id?: number;
    nom: string;
    obligatoire: boolean;
}

/** Miroir de made.archive.entite.SortFinal (backend). */
export type SortFinal = 'CONSERVER' | 'DETRUIRE' | 'TRIER';

export interface TypeDocumentDto {
    id?: number;
    nom: string;
    metaData: MetaDataDto[];
    uoId: number;
    retentionYears: number | null;
    /** true si des regex d'extraction OCR ont déjà été générées pour ce type. */
    regexGenerated?: boolean;
    /** Regex par champ, encodées en JSON (voir TypeDocument.extractionRegexJson côté serveur). */
    extractionRegexJson?: string | null;
    /** CONSERVER (défaut) / DETRUIRE / TRIER — voir SortFinal. Gouverne ce qui
     *  arrive à un document de ce type une fois en corbeille, délai de grâce
     *  écoulé (voir modifierSortFinalTypeDocument pour la modifier après coup,
     *  même si des documents sont déjà rattachés à ce type). */
    sortFinal?: SortFinal;
    /** Activité (plan de classement de l'UO) — null/absent = non classé. Se modifie via
     *  rattacherTypeAActivite (PlanClassementService), pas via create/update. */
    planClassementNoeudId?: number | null;
    /** Chemin lisible, ex. "03 Finances › 03.2 Factures". */
    activite?: string | null;
}

/**
 * PUT /api/editor/types-documents/{id}/reset-regex
 * Réinitialise les regex d'extraction OCR d'un type — elles seront
 * régénérées au prochain document de ce type. Gestion des types de
 * documents réservée aux EDITOR de leur propre UO (voir DocumentController,
 * backend) — ni ADMIN ni ADMIN_UO n'y ont plus accès.
 */
export const resetTypeDocumentRegex = async (id: number): Promise<void> => {
    try {
        await api.put(`/editor/types-documents/${id}/reset-regex`);
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * PUT /api/editor/types-documents/{id}/regex
 * Corrige manuellement les regex d'extraction OCR d'un type — un champ par
 * métadonnée existante ; le serveur valide que chaque regex compile avant
 * d'enregistrer.
 */
export const modifierTypeDocumentRegex = async (
    id: number,
    regexParChamp: Record<string, string>
): Promise<TypeDocumentDto> => {
    try {
        const response = await api.put(`/editor/types-documents/${id}/regex`, regexParChamp);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * GET /api/editor/types-documents/uo/{uoId}
 * Scopé à la propre UO de l'éditeur (le serveur refuse toute autre UO —
 * voir UniteOrganisationnelleService.estEditeurDeUO). À NE PAS confondre
 * avec getTypeDocumentsByUO ci-dessous (endpoint ADMIN/ADMIN_UO différent,
 * réservé à des besoins de LECTURE sans rapport avec la gestion des types :
 * FixityCheckPanel, DossiersPanel).
 */
export const getTypeDocumentsByUOEditor = async (uoId: number): Promise<TypeDocumentDto[]> => {
    try {
        const response = await api.get(`/editor/types-documents/uo/${uoId}`);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * GET /api/admin_uo/types-documents/uo/{uoId}
 * Réservé ADMIN/ADMIN_UO — LECTURE seule, pour des besoins qui n'ont rien à
 * voir avec la gestion des types de documents elle-même (réservée aux
 * EDITOR, voir getTypeDocumentsByUOEditor) : regrouper les cibles d'un
 * contrôle d'intégrité par type d'origine (FixityCheckPanel), lister les
 * types attendus dans un dossier (DossiersPanel).
 */
export const getTypeDocumentsByUO = async (uoId: number): Promise<TypeDocumentDto[]> => {
    try {
        const response = await api.get(`/admin_uo/types-documents/uo/${uoId}`);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * GET /api/user/types-documents?uoId=
 * Types de documents visibles par l'utilisateur connecté — ouvert à
 * TOUT rôle (contrairement à getAllTypeDocuments/getTypeDocumentsByUO
 * ci-dessus, réservés à ADMIN/ADMIN_UO). Sert le filtre "Type de document"
 * de "Documents accessibles", utilisé par tous les tableaux de bord.
 * uoId omis = tout le périmètre visible de l'appelant (sa propre UO pour
 * EDITOR/USER, son sous-arbre pour ADMIN_UO, tout pour ADMIN).
 */
export const getTypeDocumentsVisibles = async (uoId?: number | null): Promise<TypeDocumentDto[]> => {
    try {
        const response = await api.get('/user/types-documents', {
            params: uoId ? { uoId } : {},
        });
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

/**
 * POST /api/editor/types-documents/{id}/sort-final
 * Modifie le sort final d'un type — DÉLIBÉRÉMENT un endpoint à part, pas
 * soumis au verrou "documents déjà rattachés" de updateTypeDocument (voir
 * TypeDocumentService.modifierSortFinal côté serveur) : contrairement au nom
 * ou aux métadonnées, c'est une décision de gouvernance purement tournée vers
 * l'avenir, modifiable à tout moment même pour un type déjà en service.
 */
export const modifierSortFinalTypeDocument = async (id: number, sortFinal: SortFinal): Promise<TypeDocumentDto> => {
    try {
        // JSON.stringify explicite (pas juste la chaîne nue) : le @RequestBody String
        // du serveur attend un littéral JSON valide ("CONSERVER", guillemets inclus) —
        // axios ne sérialise PAS automatiquement une string déjà passée en body.
        const response = await api.post(`/editor/types-documents/${id}/sort-final`, JSON.stringify(sortFinal));
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

export const createTypeDocument = async (dto: TypeDocumentDto): Promise<TypeDocumentDto> => {
    try {
        const response = await api.post('/editor/types-documents/create', dto);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

export const updateTypeDocument = async (id: number, dto: TypeDocumentDto): Promise<TypeDocumentDto> => {
    try {
        const response = await api.put(`/editor/types-documents/${id}`, dto);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

export const deleteTypeDocument = async (id: number): Promise<void> => {
    try {
        await api.delete(`/editor/types-documents/${id}`);
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

export const deleteTypeDocumentList = async (ids: number[]): Promise<void> => {
    try {
        await api.delete('/editor/types-documents/delete-list', { data: ids });
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};