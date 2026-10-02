import api from "../api";

// ═══════════════════════════════════════════════════════════════════════════
// Localisation physique — voir Backend PhysicalLocation/PhysicalLocationService
// ═══════════════════════════════════════════════════════════════════════════

export type LocationStatus = 'ACTIVE' | 'INACTIVE';

/**
 * Contrainte d'acceptation d'un point de stockage — voir LocationModeContrainte
 * côté backend. Sans effet sur un nœud chemin (storagePoint=false).
 *   - LIBRE : comportement historique, aucune contrainte.
 *   - TYPE_UNIQUE : n'accepte que des documents d'UN SEUL type de document.
 *   - DOSSIER : n'accepte que des documents rattachés à UN SEUL dossier
 *     (appartenance DIRECTE uniquement) — PLUSIEURS nœuds peuvent pointer
 *     vers le même dossier (dossier volumineux réparti sur plusieurs
 *     "boîtes"), le choix entre elles reste manuel.
 */
export type LocationModeContrainte = 'LIBRE' | 'TYPE_UNIQUE' | 'DOSSIER';

export interface PhysicalLocationDto {
    id: string;
    name: string;
    description: string | null;
    status: LocationStatus;
    storagePoint: boolean;
    /** Nombre maximal de documents (storagePoint=true seulement) — null = illimité. */
    capaciteMax: number | null;
    /** Nombre de documents actuellement rattachés (vivants, hors DELETED). */
    nombreDocuments: number;
    modeContrainte: LocationModeContrainte;
    /** Renseigné seulement si modeContrainte === 'TYPE_UNIQUE'. */
    typeDocumentAccepteId: number | null;
    typeDocumentAccepteNom: string | null;
    /** Renseigné seulement si modeContrainte === 'DOSSIER'. */
    dossierId: number | null;
    dossierNom: string | null;
    parentId: string | null;
    uniteOrganisationnelleId: number;
    cheminComplet: string;
    createdAt: string;
    createdByNom: string | null;
    updatedAt: string | null;
    updatedByNom: string | null;
}

export interface PhysicalLocationNodeDto {
    id: string;
    name: string;
    status: LocationStatus;
    storagePoint: boolean;
    capaciteMax: number | null;
    nombreDocuments: number;
    modeContrainte: LocationModeContrainte;
    typeDocumentAccepteId: number | null;
    typeDocumentAccepteNom: string | null;
    dossierId: number | null;
    dossierNom: string | null;
    children: PhysicalLocationNodeDto[];
}

export interface PhysicalLocationCreateDto {
    name: string;
    description?: string;
    storagePoint: boolean;
    parentId?: string | null;
    uniteOrganisationnelleId: number;
}

export interface PhysicalLocationUpdateDto {
    name?: string;
    description?: string;
}

/**
 * Nœud d'arborescence envoyé à creerArborescence/mettreAJourArborescence —
 * voir PhysicalLocationTreeNodeDto côté backend. id absent/undefined =
 * nouveau nœud à créer ; présent = nœud existant à renommer (modification
 * uniquement, jamais en création, storagePoint/capaciteMax/modeContrainte
 * alors ignorés côté serveur — voir definirCapaciteEmplacement/
 * definirContrainteEmplacement pour modifier ceux d'un nœud déjà en base).
 */
export interface PhysicalLocationTreeNodeDto {
    id?: string;
    name: string;
    description?: string;
    storagePoint: boolean;
    /** Uniquement pour un NOUVEAU nœud storagePoint=true. */
    capaciteMax?: number | null;
    /** Uniquement pour un NOUVEAU nœud storagePoint=true — LIBRE si omis. */
    modeContrainte?: LocationModeContrainte;
    /** Renseigné seulement si modeContrainte === 'TYPE_UNIQUE'. */
    typeDocumentId?: number | null;
    /** Renseigné seulement si modeContrainte === 'DOSSIER'. */
    dossierId?: number | null;
    children: PhysicalLocationTreeNodeDto[];
}

/** Toujours UNE SEULE racine (node) par appel — voir Javadoc backend. */
export interface PhysicalLocationArborescenceRequestDto {
    uniteOrganisationnelleId: number;
    parentId?: string | null;
    node: PhysicalLocationTreeNodeDto;
}

const extractMessage = (error: any): Error => {
    const msg = typeof error.response?.data === 'string'
        ? error.response.data
        : error.response?.data?.message;
    return msg ? new Error(msg) : error;
};

// ── Gestion COMPLÈTE (EDITOR, dans sa propre UO uniquement) — /api/editor/physical-locations ──
// Revu le 09/2026 : ADMIN/ADMIN_UO n'ont plus AUCUN droit d'écriture (retiré
// en deux temps — d'abord la création, puis tout le reste), uniquement un
// droit de LECTURE (voir plus bas). Contrôleur backend SÉPARÉ (voir
// PhysicalLocationEditorController) : la règle d'autorisation d'URL de
// SecurityConfig bloque tout ROLE_EDITOR sur /api/admin_uo/**, même avec le
// bon rôle sur la méthode elle-même — un /admin_uo/physical-locations/...
// pour l'éditeur resterait donc inatteignable.
export const creerEmplacementEditeur = async (dto: PhysicalLocationCreateDto): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.post('/editor/physical-locations', dto);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

/**
 * Crée UN emplacement et sa descendance (enfants imbriqués) en un seul appel
 * — le brouillon est construit localement côté client (voir
 * EmplacementTreeModal) avant cet unique envoi. Retourne la racine créée,
 * avec ses enfants imbriqués (même forme que l'arbre lu par
 * getArbreEmplacements).
 */
export const creerArborescence = async (dto: PhysicalLocationArborescenceRequestDto): Promise<PhysicalLocationNodeDto> => {
    try {
        const response = await api.post('/editor/physical-locations/arborescence', dto);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

/**
 * Modifie UN emplacement existant (nom + description) et sa descendance en
 * un seul appel — même principe que creerArborescence, mais pour un nœud
 * déjà en base : le brouillon envoyé pré-remplit l'arborescence RÉELLE
 * actuelle (id sur chaque nœud existant), permet de renommer n'importe quel
 * nœud existant et d'ajouter de nouveaux descendants n'importe où. Ne
 * supprime JAMAIS un nœud absent du brouillon — voir Javadoc backend.
 */
export const mettreAJourArborescence = async (rootId: string, node: PhysicalLocationTreeNodeDto): Promise<PhysicalLocationNodeDto> => {
    try {
        const response = await api.put(`/editor/physical-locations/${rootId}/arborescence`, node);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

export const modifierEmplacement = async (id: string, dto: PhysicalLocationUpdateDto): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.put(`/editor/physical-locations/${id}`, dto);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

export const changerTypeStockage = async (id: string, storagePoint: boolean): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.put(`/editor/physical-locations/${id}/type-stockage`, null, {
            params: { storagePoint },
        });
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

/** capaciteMax null = retire la limite (illimité). Modifiable à tout moment
 *  (contrairement à definirContrainteEmplacement), refusé seulement si
 *  inférieur au nombre de documents déjà rattachés. */
export const definirCapaciteEmplacement = async (id: string, capaciteMax: number | null): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.put(`/editor/physical-locations/${id}/capacite`, null, {
            params: capaciteMax != null ? { capaciteMax } : {},
        });
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

/** Uniquement pour un nœud VIDE (aucun document rattaché) — voir Javadoc backend. */
export const definirContrainteEmplacement = async (
    id: string, modeContrainte: LocationModeContrainte, typeDocumentId?: number | null, dossierId?: number | null
): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.put(`/editor/physical-locations/${id}/contrainte`, null, {
            params: {
                modeContrainte,
                ...(typeDocumentId != null ? { typeDocumentId } : {}),
                ...(dossierId != null ? { dossierId } : {}),
            },
        });
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

/** nouveauParentId undefined/null = devient une nouvelle racine. */
export const deplacerEmplacement = async (id: string, nouveauParentId: string | null): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.put(`/editor/physical-locations/${id}/deplacer`, null, {
            params: nouveauParentId ? { nouveauParentId } : {},
        });
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

export const desactiverEmplacement = async (id: string): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.put(`/editor/physical-locations/${id}/desactiver`);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

export const reactiverEmplacement = async (id: string): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.put(`/editor/physical-locations/${id}/reactiver`);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

export const supprimerEmplacement = async (id: string): Promise<void> => {
    try {
        await api.delete(`/editor/physical-locations/${id}`);
    } catch (error: any) {
        throw extractMessage(error);
    }
};

// ── Lecture (tout ROLE_USER, scopée comme les documents) — /api/user/physical-locations ──

export const getEmplacementById = async (id: string): Promise<PhysicalLocationDto> => {
    try {
        const response = await api.get(`/user/physical-locations/${id}`);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

export const getArbreEmplacements = async (uoId: number): Promise<PhysicalLocationNodeDto[]> => {
    try {
        const response = await api.get(`/user/physical-locations/uo/${uoId}/arbre`);
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};

/**
 * Emplacements assignables à un document (points de stockage ACTIFS) pour une
 * UO — typeDocumentId/dossierId (optionnels) filtrent par compatibilité
 * (voir LocationModeContrainte côté backend) : un nœud LIBRE revient
 * toujours, un nœud TYPE_UNIQUE seulement si typeDocumentId correspond, un
 * nœud DOSSIER seulement si dossierId correspond. Omis = comportement
 * historique (tous les points de stockage actifs, sans filtrage).
 */
export const getEmplacementsDisponibles = async (
    uoId: number, typeDocumentId?: number | null, dossierId?: number | null
): Promise<PhysicalLocationDto[]> => {
    try {
        const response = await api.get(`/user/physical-locations/uo/${uoId}/disponibles`, {
            params: {
                ...(typeDocumentId != null ? { typeDocumentId } : {}),
                ...(dossierId != null ? { dossierId } : {}),
            },
        });
        return response.data;
    } catch (error: any) {
        throw extractMessage(error);
    }
};
