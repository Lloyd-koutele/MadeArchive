// services/document/TypedocumentService.ts
import api from "../api";

export interface MetaDataDto {
    id?: number;
    nom: string;
    obligatoire: boolean;
}

export interface TypeDocumentDto {
    id?: number;
    nom: string;
    metaData: MetaDataDto[];
    uoId: number;
    retentionYears: number | null;
    periodGrace: number | null;
    /** true si des regex d'extraction OCR ont déjà été générées pour ce type. */
    regexGenerated?: boolean;
    /** Regex par champ, encodées en JSON (voir TypeDocument.extractionRegexJson côté serveur). */
    extractionRegexJson?: string | null;
}

/**
 * PUT /api/admin_uo/types-documents/{id}/reset-regex
 * Réinitialise les regex d'extraction OCR d'un type — elles seront
 * régénérées au prochain document de ce type. Réservé à ADMIN/ADMIN_UO.
 */
export const resetTypeDocumentRegex = async (id: number): Promise<void> => {
    try {
        await api.put(`/admin_uo/types-documents/${id}/reset-regex`);
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

// Réservé ADMIN côté serveur — vue globale non scopée
export const getAllTypeDocuments = async (): Promise<TypeDocumentDto[]> => {
    try {
        const response = await api.get('/admin_uo/types-documents');
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

export const getTypeDocumentById = async (id: number): Promise<TypeDocumentDto> => {
    try {
        const response = await api.get(`/admin_uo/types-documents/${id}`);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

// Scopé à une UO précise — utilisé par ADMIN_UO, qui n'a pas accès à getAllTypeDocuments
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

export const createTypeDocument = async (dto: TypeDocumentDto): Promise<TypeDocumentDto> => {
    try {
        const response = await api.post('/admin_uo/types-documents/create', dto);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

export const updateTypeDocument = async (id: number, dto: TypeDocumentDto): Promise<TypeDocumentDto> => {
    try {
        const response = await api.put(`/admin_uo/types-documents/${id}`, dto);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

export const deleteTypeDocument = async (id: number): Promise<void> => {
    try {
        await api.delete(`/admin_uo/types-documents/${id}`);
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

export const deleteTypeDocumentList = async (ids: number[]): Promise<void> => {
    try {
        await api.delete('/admin_uo/types-documents/delete-list', { data: ids });
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};