import api from "../api";

export const getMe = async(id) =>{
    try{
        const response = await api.get(`/user/me/${id}`);
        return response.data;
    }catch(error){
        console.error('Détails de l\'erreur:', error.response?.data || error);
        throw error.response?.data?.message
                ? new Error(error.response.data.message)
                : error;
    }
}

export const updateMe = async (id: string, data: object) => {
    try {
        const response = await api.put(`/user/update-me/${id}`, data);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
}