import api from '../api';
import type { MembreDto, GroupeMembresResponse } from '../document/GroupeService';

/**
 * Gestion du groupe d'accès d'un dossier PRIVÉ — même schéma que
 * services/document/GroupeService.ts (documents), mais le "propriétaire" est
 * ici le créateur du dossier, pas un uploadeur de document.
 */

export const getMembresDossier = async (dossierId: number): Promise<GroupeMembresResponse> => {
    try {
        const response = await api.get(`/user/dossiers/${dossierId}/groupe/membres`);
        return response.data;
    } catch (error: any) {
        throw new Error(error.response?.data || error.message);
    }
};

export const getDisponiblesDossier = async (dossierId: number): Promise<MembreDto[]> => {
    try {
        const response = await api.get(`/user/dossiers/${dossierId}/groupe/disponibles`);
        return response.data;
    } catch (error: any) {
        throw new Error(error.response?.data || error.message);
    }
};

export const ajouterMembreDossier = async (dossierId: number, nouveauMembreId: string): Promise<void> => {
    try {
        await api.post(`/user/dossiers/${dossierId}/groupe/membres`, null, {
            params: { nouveauMembreId }
        });
    } catch (error: any) {
        throw new Error(error.response?.data || error.message);
    }
};

export const retirerMembreDossier = async (dossierId: number, membreId: string): Promise<void> => {
    try {
        await api.delete(`/user/dossiers/${dossierId}/groupe/membres/${membreId}`);
    } catch (error: any) {
        throw new Error(error.response?.data || error.message);
    }
};
