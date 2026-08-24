import api from '../api';
import type { MembreDto, GroupeMembresResponse } from '../document/GroupeService';

/**
 * Gestion du groupe d'accès d'un projet PRIVÉ — même schéma que
 * services/document/GroupeService.ts (documents), mais le "propriétaire" est
 * ici le créateur du projet, pas un uploadeur de document.
 */

export const getMembresProjet = async (projetId: number): Promise<GroupeMembresResponse> => {
    try {
        const response = await api.get(`/user/projets/${projetId}/groupe/membres`);
        return response.data;
    } catch (error: any) {
        throw new Error(error.response?.data || error.message);
    }
};

export const getDisponiblesProjet = async (projetId: number): Promise<MembreDto[]> => {
    try {
        const response = await api.get(`/user/projets/${projetId}/groupe/disponibles`);
        return response.data;
    } catch (error: any) {
        throw new Error(error.response?.data || error.message);
    }
};

export const ajouterMembreProjet = async (projetId: number, nouveauMembreId: string): Promise<void> => {
    try {
        await api.post(`/user/projets/${projetId}/groupe/membres`, null, {
            params: { nouveauMembreId }
        });
    } catch (error: any) {
        throw new Error(error.response?.data || error.message);
    }
};

export const retirerMembreProjet = async (projetId: number, membreId: string): Promise<void> => {
    try {
        await api.delete(`/user/projets/${projetId}/groupe/membres/${membreId}`);
    } catch (error: any) {
        throw new Error(error.response?.data || error.message);
    }
};
