import api from '../api';

export type NotificationType = 'DOCUMENT_CORROMPU' | 'DOCUMENT_AJOUTE' | 'PROJET_CREE';

export interface NotificationDto {
    id:       number;
    type:     NotificationType;
    message:  string;
    createAt: string;
    read:     boolean;
}

/**
 * GET /api/user/notifications
 */
export const getMesNotifications = async (): Promise<NotificationDto[]> => {
    try {
        const response = await api.get('/user/notifications');
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur chargement notifications'
        );
    }
};

/**
 * GET /api/user/notifications/non-lues/count
 */
export const countNotificationsNonLues = async (): Promise<number> => {
    try {
        const response = await api.get('/user/notifications/non-lues/count');
        return response.data.count ?? 0;
    } catch {
        return 0;
    }
};

/**
 * PUT /api/user/notifications/{id}/lue
 */
export const marquerNotificationLue = async (id: number): Promise<void> => {
    try {
        await api.put(`/user/notifications/${id}/lue`);
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur mise à jour notification'
        );
    }
};

/**
 * PUT /api/user/notifications/lues
 */
export const marquerToutesLues = async (): Promise<void> => {
    try {
        await api.put('/user/notifications/lues');
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur mise à jour notifications'
        );
    }
};
