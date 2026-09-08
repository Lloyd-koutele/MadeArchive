// services/document/FixityCheckService.ts
import api from '../api';

export type FixityCheckScope = 'TYPES' | 'UO' | 'TOUT';

export interface FixityCheckRequest {
    scope: FixityCheckScope;
    typeDocumentIds?: number[];
    uoIds?: number[];
}

/**
 * POST /api/admin_uo/fixity-check
 * Déclenche manuellement le contrôle d'intégrité (fixity check) sur le
 * périmètre demandé — voir FixityCheckTriggerService (backend) pour les
 * règles d'autorité et le cooldown de 6h par périmètre individuel.
 *
 * Répond dès que la vérification a DÉMARRÉ (elle tourne en arrière-plan) —
 * le résultat réel arrive plus tard sous forme de notification.
 */
export const declencherFixityCheck = async (request: FixityCheckRequest): Promise<string> => {
    try {
        const response = await api.post('/admin_uo/fixity-check', request);
        return response.data?.message ?? 'Vérification lancée.';
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur lors du déclenchement du contrôle'
        );
    }
};
