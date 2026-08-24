// services/organisation/UOService.ts
import api from "../api"

export const getAllUOs = async () => {
    try {
        const response = await api.get('/admin_uo/uo');
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const getMyUO = async () => {
    try {
        // /user/uo/me (ROLE_USER, hérité par EDITOR/ADMIN_UO/ADMIN) — pas /admin_uo/uo/me :
        // ce dernier est inatteignable pour EDITOR/USER, la règle d'URL exigeant ROLE_ADMIN_UO.
        const response = await api.get('/user/uo/me');
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const getUO = async (id) => {
    try {
        const response = await api.get(`/admin_uo/uo/${id}`);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const getUOsFilles = async (id) => {
    try {
        const response = await api.get(`/admin_uo/uo/${id}/filles`);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

// Sous-arbre complet à plat (racine + tous descendants) — alimente l'arbre de navigation
export const getSousArbre = async (id: number) => {
    try {
        const response = await api.get(`/admin_uo/uo/${id}/sous-arbre`);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const createUO = async (uoData) => {
    try {
        const response = await api.post('/admin_uo/uo', uoData);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const updateUO = async (id, uoData) => {
    try {
        if (!id) throw new Error('ID UO manquant');
        const response = await api.put(`/admin_uo/uo/${id}`, uoData);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

// Déplacement vers la racine — réservé ADMIN côté serveur
export const deplacerVersRacine = async (id: number) => {
    try {
        const response = await api.put(`/admin_uo/uo/${id}/racine`);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const deleteUO = async (id) => {
    try {
        if (!id) throw new Error('ID UO manquant');
        await api.delete(`/admin_uo/uo/${id}`);
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const getMembresUO = async (id) => {
    try {
        const response = await api.get(`/admin_uo/uo/${id}/membres`);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const ajouterMembreUO = async (uoId, userId) => {
    try {
        const response = await api.post(`/admin_uo/uo/${uoId}/membres/${userId}`);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const retirerMembreUO = async (uoId, userId) => {
    try {
        const response = await api.delete(`/admin_uo/uo/${uoId}/membres/${userId}`);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const retirerMembreEtAdmin = async (uoId, userId) => {
    try {
        const response = await api.delete(`/admin_uo/uo/${uoId}/membres/${userId}/admin`);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const transfererMembreUO = async (nouvelUoId: number, userId: string) => {
    try {
        const response = await api.put(`/admin_uo/uo/${nouvelUoId}/membres/${userId}/transferer`);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}