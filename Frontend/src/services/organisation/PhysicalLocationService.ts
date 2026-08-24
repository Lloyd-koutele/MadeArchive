import api from "../api";

// ═══════════════════════════════════════════════════════════════════════════
// Localisation physique — voir Backend PhysicalLocation/PhysicalLocationService
// ═══════════════════════════════════════════════════════════════════════════

export type LocationStatus = 'ACTIVE' | 'INACTIVE';

export interface PhysicalLocationDto {
    id: string;
    code: string;
    name: string;
    description: string | null;
    status: LocationStatus;
    storagePoint: boolean;
    parentId: string | null;
    uniteOrganisationnelleId: number;
    cheminComplet: string;
    createdAt: string;
    createdByNom: string | null;
    updatedAt: string | null;
    updatedByNom: string | null;
}

export interface PhysicalLocationNodeDto {
    id: string;
    code: string;
    name: string;
    status: LocationStatus;
    storagePoint: boolean;
    children: PhysicalLocationNodeDto[];
}

export interface PhysicalLocationCreateDto {
    code: string;
    name: string;
    description?: string;
    storagePoint: boolean;
    parentId?: string | null;
    uniteOrganisationnelleId: number;
}

export interface PhysicalLocationUpdateDto {
    code?: string;
    name?: string;
    description?: string;
}

const extractMessage = (error: any): Error => {
    const msg = typeof error.response?.data === 'string'
        ? error.response.data
        : error.response?.data?.message;
    return msg ? new Error(msg) : error;
};

// ── Gestion (ADMIN / ADMIN_UO) — /api/admin_uo/physical-locations ──────────

export const creerEmplacement = async (dto: PhysicalLocationCreateDto): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.post('/admin_uo/physical-locations', dto);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

export const modifierEmplacement = async (id: string, dto: PhysicalLocationUpdateDto): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.put(`/admin_uo/physical-locations/${id}`, dto);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

export const changerTypeStockage = async (id: string, storagePoint: boolean): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.put(`/admin_uo/physical-locations/${id}/type-stockage`, null, {
            params: { storagePoint },
        });
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

export const desactiverEmplacement = async (id: string): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.put(`/admin_uo/physical-locations/${id}/desactiver`);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

export const reactiverEmplacement = async (id: string): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.put(`/admin_uo/physical-locations/${id}/reactiver`);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

export const supprimerEmplacement = async (id: string): Promise<void> => {
    try {
        await api.delete(`/admin_uo/physical-locations/${id}`);
    } catch (error: any) {
        throw extractMessage(error);
    }
};

// ── Lecture (tout ROLE_USER, scopée comme les documents) — /api/user/physical-locations ──

export const getEmplacementById = async (id: string): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.get(`/user/physical-locations/${id}`);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

export const getArbreEmplacements = async (uoId: number): Promise<PhysicalLocationNodeDto[]> => {
    try {
        const response = await api.get(`/user/physical-locations/uo/${uoId}/arbre`);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

/** Emplacements assignables à un document (points de stockage ACTIFS) pour une UO. */
export const getEmplacementsDisponibles = async (uoId: number): Promise<PhysicalLocationDto[]> => {
    try {
        const response = await api.get(`/user/physical-locations/uo/${uoId}/disponibles`);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};
