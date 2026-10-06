// services/organisation/PlanClassementService.ts
import api from '../api';

/** Miroir de made.archive.dto.PlanClassementNoeudDto. */
export interface PlanClassementNoeudDto {
    id: number;
    code: string;
    libelle: string;
    parentId: number | null;
    /** Types de documents directement rattachés à ce nœud (hors sous-activités). */
    nbTypes: number;
    /** Documents dont l'activité effective est ce nœud (la leur, ou celle de leur type). */
    nbDocuments: number;
    /** true si ce nœud ou l'une de ses sous-activités a des documents : plus modifiable ni supprimable. */
    verrouille: boolean;
    children: PlanClassementNoeudDto[];
}

/** Nœud aplati pour un <select> : libellé indenté + chemin complet. */
export interface PlanClassementOption {
    id: number;
    label: string;
    chemin: string;
    profondeur: number;
}

const erreur = (error: any): never => {
    throw error.response?.data?.message ? new Error(error.response.data.message) : error;
};

/** GET /api/editor/plan-classement/uo/{uoId} — arbre complet du plan de l'UO (éditeur de cette UO). */
export const getPlanClassement = async (uoId: number): Promise<PlanClassementNoeudDto[]> => {
    try {
        return (await api.get(`/editor/plan-classement/uo/${uoId}`)).data;
    } catch (e) { return erreur(e); }
};

/** Le code (01, 01.1, 01.1.1…) est attribué par le serveur d'après la position dans l'arbre. */
export const creerNoeudPlanClassement = async (
    uoId: number, libelle: string, parentId: number | null,
): Promise<PlanClassementNoeudDto> => {
    try {
        return (await api.post('/editor/plan-classement', { uoId, libelle, parentId })).data;
    } catch (e) { return erreur(e); }
};

export const modifierNoeudPlanClassement = async (
    id: number, libelle: string,
): Promise<PlanClassementNoeudDto> => {
    try {
        return (await api.put(`/editor/plan-classement/${id}`, { libelle })).data;
    } catch (e) { return erreur(e); }
};

/** parentId null = déplacer à la racine du plan. */
export const deplacerNoeudPlanClassement = async (id: number, parentId: number | null): Promise<void> => {
    try {
        await api.put(`/editor/plan-classement/${id}/deplacer`, null, { params: parentId != null ? { parentId } : {} });
    } catch (e) { erreur(e); }
};

export const supprimerNoeudPlanClassement = async (id: number): Promise<void> => {
    try {
        await api.delete(`/editor/plan-classement/${id}`);
    } catch (e) { erreur(e); }
};

/**
 * Rattache (noeudId) ou détache (null) un type de document à une activité de
 * son UO — possible à tout moment, même si le type a déjà des documents (voir
 * PlanClassementService.rattacherType côté serveur).
 */
export const rattacherTypeAActivite = async (typeId: number, noeudId: number | null): Promise<void> => {
    try {
        await api.put(`/editor/plan-classement/types/${typeId}`, null, { params: noeudId != null ? { noeudId } : {} });
    } catch (e) { erreur(e); }
};

/** Aplatit l'arbre en liste ordonnée (parents avant enfants) pour un <select>. */
export const aplatirPlanClassement = (
    noeuds: PlanClassementNoeudDto[], prefixeChemin = '', profondeur = 0,
): PlanClassementOption[] =>
    noeuds.flatMap(n => {
        const chemin = `${prefixeChemin}${n.code} ${n.libelle}`;
        return [
            { id: n.id, label: `${'  '.repeat(profondeur)}${n.code} ${n.libelle}`, chemin, profondeur },
            ...aplatirPlanClassement(n.children, `${chemin} › `, profondeur + 1),
        ];
    });
