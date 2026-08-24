import api from "../api";

export interface SearchRequestDto {
    query: string;
    typeDocumentId?: number | null;
    page: number;
    hitsPerPage: number;
}

export interface SearchResultItemDto {
    documentId: string;
    titre: string;
    typeDocument: string;
    access: string;
    status: string;
    retentionUntil: string;
}

export interface SearchResultDto {
    totalHits: number;
    page: number;
    hitsPerPage: number;
    totalPages: number;
    results: SearchResultItemDto[];
}

export const searchDocuments = async (request: SearchRequestDto): Promise<SearchResultDto> => {
    try {
        const response = await api.post('/user/documents/search', request);
        return response.data;
    } catch (error: any) {
        throw new Error(error.response?.data || error.message || "Erreur lors de la recherche");
    }
};