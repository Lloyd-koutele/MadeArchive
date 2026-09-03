import api from '../api';

// ═══════════════════════════════════════════════════════════════════════════
// TYPES
// ═══════════════════════════════════════════════════════════════════════════

export interface CreerProjetDto {
    nom: string;
    description?: string;
    uoId: number;
    typeDocumentIds?: number[];
    /** "PUBLIC" (défaut si absent) ou "PRIVE". */
    access?: string;
    /** Membres initiaux si access === "PRIVE" — le créateur est ajouté automatiquement. */
    groupeMembresIds?: string[];
}

export interface UserSummaryDto {
    id: string;
    nom: string;
    prenom: string;
    email: string;
}

/**
 * Forme brute renvoyée par POST /projets et GET /projets/uo/{uoId}
 * (l'entité Projet — uniteOrganisationnelle est masquée côté serveur).
 */
export interface ProjetDto {
    id: number;
    nom: string;
    description: string | null;
    creePar: UserSummaryDto;
    createAt: string;
}

export interface TypeAttenduDto {
    typeDocumentId: number;
    nom: string;
    nombreDocuments: number;
    fourni: boolean;
}

/**
 * Forme renvoyée par GET /projets/{id} — inclut la checklist des types attendus.
 */
export interface ProjetDetailDto {
    id: number;
    nom: string;
    description: string | null;
    uoId: number;
    uoNom: string;
    creePar: string;
    createAt: string;
    typesAttendus: TypeAttenduDto[];
    /** "PUBLIC" ou "PRIVE". */
    access: string;
    /** true si l'utilisateur connecté est éditeur de l'UO du projet — peut ajouter/retirer des types attendus. */
    peutGererTypes: boolean;
    /** true si l'utilisateur connecté est le CRÉATEUR du projet — seul habilité à le supprimer et à gérer ses droits d'accès. */
    peutGererAcces: boolean;
}

// ═══════════════════════════════════════════════════════════════════════════
// API
// ═══════════════════════════════════════════════════════════════════════════

/**
 * POST /api/editor/projets
 */
export const creerProjet = async (dto: CreerProjetDto): Promise<ProjetDto> => {
    try {
        const response = await api.post('/editor/projets', dto);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur création du projet'
        );
    }
};

/**
 * PUT /api/editor/projets/{id} — nom/description uniquement, jamais les
 * types attendus (voir ajouterTypesAttendus/retirerTypeAttendu) ni l'accès.
 */
export const modifierProjet = async (
    id: number,
    dto: { nom: string; description?: string }
): Promise<ProjetDto> => {
    try {
        const response = await api.put(`/editor/projets/${id}`, dto);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur modification du projet'
        );
    }
};

/**
 * GET /api/user/projets/uo/{uoId} — lecture seule, ouverte à tout utilisateur
 * authentifié (ROLE_USER), pas seulement EDITOR/ADMIN_UO/ADMIN.
 */
export const getProjetsDeUO = async (uoId: number): Promise<ProjetDto[]> => {
    try {
        const response = await api.get(`/user/projets/uo/${uoId}`);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur chargement des projets'
        );
    }
};

/**
 * GET /api/user/projets/{id} — lecture seule, voir getProjetsDeUO.
 */
export const getProjetDetail = async (id: number): Promise<ProjetDetailDto> => {
    try {
        const response = await api.get(`/user/projets/${id}`);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur chargement du projet'
        );
    }
};

/**
 * POST /api/editor/projets/{id}/types — additif.
 */
export const ajouterTypesAttendus = async (id: number, typeDocumentIds: number[]): Promise<ProjetDto> => {
    try {
        const response = await api.post(`/editor/projets/${id}/types`, typeDocumentIds);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? "Erreur ajout des types attendus"
        );
    }
};

/**
 * DELETE /api/editor/projets/{id}/types/{typeId} — refusé si des documents de
 * ce type existent déjà dans ce projet précis. Réservé aux éditeurs de l'UO.
 */
export const retirerTypeAttendu = async (id: number, typeId: number): Promise<ProjetDto> => {
    try {
        const response = await api.delete(`/editor/projets/${id}/types/${typeId}`);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? "Erreur retrait du type attendu"
        );
    }
};

/**
 * DELETE /api/editor/projets/{id} — uniquement si vide, réservé au créateur du projet.
 */
export const supprimerProjet = async (id: number): Promise<void> => {
    try {
        await api.delete(`/editor/projets/${id}`);
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur suppression du projet'
        );
    }
};
