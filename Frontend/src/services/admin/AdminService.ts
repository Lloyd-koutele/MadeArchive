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

