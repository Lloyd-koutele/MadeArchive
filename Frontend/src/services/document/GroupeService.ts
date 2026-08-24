import api from "../api";

export interface MembreDto {
    id: string;
    nom: string;
    prenom: string;
    email: string;
}

export interface GroupeMembresResponse {
    membres: MembreDto[];
    uploadeurId: string;
    /** true si l'utilisateur connecté est l'uploadeur — seul cas où il peut gérer (ajouter/retirer) le groupe. */
    peutGerer: boolean;
}

/**
 * Ouvert à tout membre du groupe (pas seulement l'uploadeur) — voir
 * GroupeAccessService.getMembres côté serveur. `peutGerer` indique au client
 * s'il doit afficher les contrôles d'ajout/retrait pour l'utilisateur courant.
 */
export const getMembres = async (documentId: string): Promise<GroupeMembresResponse> => {
    try {
        const response = await api.get(`/user/documents/${documentId}/groupe/membres`);
        return response.data;
    } catch (error: any) {
        throw new Error(error.response?.data || error.message);
    }
};

export const getDisponibles = async (documentId: string): Promise<MembreDto[]> => {
    try {
        const response = await api.get(`/user/documents/${documentId}/groupe/disponibles`);
        return response.data;
    } catch (error: any) {
        throw new Error(error.response?.data || error.message);
    }
};

export const ajouterMembre = async (documentId: string, nouveauMembreId: string): Promise<void> => {
    try {
        await api.post(`/user/documents/${documentId}/groupe/membres`, null, {
            params: { nouveauMembreId }
        });
    } catch (error: any) {
        throw new Error(error.response?.data || error.message);
    }
};

export const retirerMembre = async (documentId: string, membreId: string): Promise<void> => {
    try {
        await api.delete(`/user/documents/${documentId}/groupe/membres/${membreId}`);
    } catch (error: any) {
        throw new Error(error.response?.data || error.message);
    }
};