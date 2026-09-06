// services/admin/AdminService.ts
import api from "../api"

export const createUser = async (userData, uoIds: number[] = []) => {
    try {
        const params = new URLSearchParams();
        uoIds.forEach((id) => params.append('uoIds', String(id)));
        const response = await api.post(`/admin_uo/users/create-user?${params.toString()}`, userData);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const updateUser = async (id: string, userData, uoId?: number | null) => {
    try {
        if (!id) {
            throw new Error('ID utilisateur manquant');
        }
        const params = new URLSearchParams();
        if (uoId !== undefined && uoId !== null) {
            params.append('uoId', String(uoId));
        }
        const query = params.toString();
        const url = `/admin_uo/users/update-user/${id}${query ? `?${query}` : ''}`;
        const response = await api.put(url, userData);
        return response.data;
    } catch (error) {
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const updateUserStatus = async(id, userData) => {
    try{
        if (!id) {
            throw new Error('ID utilisateur manquant');
        }
        const response = await api.put(`/admin_uo/users/status/${id}`, userData);
        return response.data;
    }catch(error){
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

// DEMANDE de suppression — bloque le compte immédiatement (réversible), puis
// exécute réellement l'action après un délai de grâce de 2 jours (réelle si le
// compte n'a jamais servi, sinon logique et irréversible : mot de passe
// invalidé, clé PKI révoquée si EDITOR ; voir UserService.demanderSuppression
// côté serveur). Annulable jusque-là via annulerSuppressionUtilisateur.
export const supprimerUtilisateur = async(id: string) => {
    try{
        if (!id) {
            throw new Error('ID utilisateur manquant');
        }
        const response = await api.delete(`/admin_uo/users/${id}`);
        return response.data;
    }catch(error){
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

// Annule une suppression en attente — n'importe quel admin peut le faire, pas
// seulement celui qui l'a demandée (c'est le levier de sécurité contre une
// suppression demandée par un compte ADMIN malveillant ou compromis).
export const annulerSuppressionUtilisateur = async(id: string) => {
    try{
        if (!id) {
            throw new Error('ID utilisateur manquant');
        }
        const response = await api.put(`/admin_uo/users/${id}/annuler-suppression`);
        return response.data;
    }catch(error){
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

// Vue globale — réservée à ADMIN côté serveur
export const getAllUsers = async() =>{
    try{
        const response = await api.get('/admin_uo/users');
        return response.data;
    }catch(error){
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

// Navigation ADMIN_UO — utilisateurs membres d'une UO précise
export const getUsersByUO = async(uoId: number) => {
    try{
        const response = await api.get(`/admin_uo/users/uo/${uoId}`);
        return response.data;
    }catch(error){
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const getActiveUsers = async() =>{
    try{
        const response = await api.get('/admin_uo/users/actifs');
        return response.data;
    }catch(error){
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const getInActiveUsers = async() =>{
    try{
        const response = await api.get('/admin_uo/users/inactifs');
        return response.data;
    }catch(error){
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

