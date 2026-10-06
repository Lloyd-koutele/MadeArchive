import api from '../api';

// ═══════════════════════════════════════════════════════════════════════════
// TYPES
// ═══════════════════════════════════════════════════════════════════════════

export interface CreerDossierDto {
    nom: string;
    uoId: number;
    /** null/absent = dossier racine de l'UO ; sinon dossier parent (même UO). */
    parentId?: number | null;
    typeDocumentIds?: number[];
    /** "PUBLIC" (défaut si absent) ou "PRIVE" — ignoré si le parent est déjà PRIVÉ (l'enfant est alors forcé PRIVÉ). */
    access?: string;
    /** Membres initiaux si le dossier créé est PRIVE — le créateur est ajouté automatiquement.
     *  Sous un parent PRIVÉ, ces membres s'AJOUTENT à ceux hérités du parent. */
    groupeMembresIds?: string[];
}

export interface UserSummaryDto {
    id: string;
    nom: string;
    prenom: string;
    email: string;
}

/**
 * Forme brute renvoyée par POST /dossiers et GET /dossiers/uo/{uoId}
 * (l'entité Dossier — uniteOrganisationnelle est masquée côté serveur).
 */
export interface DossierDto {
    id: number;
    nom: string;
    /** true si ce dossier ou l'un de ses sous-dossiers contient des documents : plus de renommage, déplacement ni suppression. */
    verrouille?: boolean;
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
 * Forme renvoyée par GET /dossiers/{id} — inclut la checklist des types attendus.
 */
export interface DossierDetailDto {
    id: number;
    nom: string;
    uoId: number;
    uoNom: string;
    /** null si dossier racine de l'UO. */
    parentId: number | null;
    parentNom: string | null;
    /** Fil d'Ariane complet, ex. "Contrats / 2026". */
    cheminComplet: string;
    creePar: string;
    createAt: string;
    typesAttendus: TypeAttenduDto[];
    /** "PUBLIC" ou "PRIVE". */
    access: string;
    /** true si l'utilisateur connecté est éditeur de l'UO du dossier — peut ajouter/retirer des types attendus. */
    peutGererTypes: boolean;
    /** true si l'utilisateur connecté est le CRÉATEUR du dossier — seul habilité à le supprimer et à gérer ses droits d'accès. */
    peutGererAcces: boolean;
    /** true si l'utilisateur connecté peut basculer PUBLIC ↔ PRIVÉ ce dossier
     *  (reste true même si le dossier est actuellement PUBLIC, contrairement à peutGererAcces). */
    peutModifierAcces: boolean;
    /** true si ce dossier ou l'un de ses sous-dossiers contient des documents : plus de renommage, déplacement ni suppression. */
    verrouille?: boolean;
}

// ═══════════════════════════════════════════════════════════════════════════
// API
// ═══════════════════════════════════════════════════════════════════════════

/**
 * POST /api/editor/dossiers
 */
export const creerDossier = async (dto: CreerDossierDto): Promise<DossierDto> => {
    try {
        const response = await api.post('/editor/dossiers', dto);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur création du dossier'
        );
    }
};

/**
 * PUT /api/editor/dossiers/{id} — nom uniquement, jamais les
 * types attendus (voir ajouterTypesAttendus/retirerTypeAttendu) ni l'accès
 * (voir modifierAccesDossier ci-dessous).
 */
export const modifierDossier = async (
    id: number,
    dto: { nom: string }
): Promise<DossierDto> => {
    try {
        const response = await api.put(`/editor/dossiers/${id}`, dto);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur modification du dossier'
        );
    }
};

/**
 * PUT /api/editor/dossiers/{id}/acces — bascule PUBLIC ↔ PRIVÉ après coup.
 * Réservé à un éditeur de la propre UO du dossier s'il est actuellement
 * PUBLIC, ou membre de son groupe d'accès s'il est déjà PRIVÉ (voir
 * DossierService.modifierAcces côté serveur). Fait suivre le changement aux
 * documents du dossier qui partagent son groupe — jamais ceux ayant leur
 * propre confidentialité indépendante. groupeMembresIds n'a d'effet que si
 * access passe à 'PRIVE' (membres initiaux du nouveau groupe, en plus de
 * l'éditeur qui fait la demande).
 */
export const modifierAccesDossier = async (
    id: number, access: 'PUBLIC' | 'PRIVE', groupeMembresIds?: string[]
): Promise<DossierDto> => {
    try {
        const response = await api.put(`/editor/dossiers/${id}/acces`, { access, groupeMembresIds });
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? "Erreur changement d'accès du dossier"
        );
    }
};

/**
 * PUT /api/editor/dossiers/{id}/acces/cascader-public — cascade OPTIONNELLE
 * du passage PUBLIC vers toute la descendance, JAMAIS automatique (voir
 * modifierAccesDossier) : à appeler séparément, seulement si l'éditeur
 * confirme dans le modal d'alerte affiché juste après avoir rendu ce dossier
 * public. Refusé si ce dossier n'est pas déjà public lui-même.
 */
export const cascaderAccesPublicVersDescendants = async (id: number): Promise<DossierDto> => {
    try {
        const response = await api.put(`/editor/dossiers/${id}/acces/cascader-public`);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? "Erreur lors de la cascade vers les sous-dossiers"
        );
    }
};

/**
 * GET /api/user/dossiers/uo/{uoId} — lecture seule, ouverte à tout utilisateur
 * authentifié (ROLE_USER), pas seulement EDITOR/ADMIN_UO/ADMIN. parentId
 * absent/null = dossiers racine de l'UO ; sinon les enfants DIRECTS de ce
 * dossier (voir DossierService.getDossiersDeUO côté serveur).
 */
export const getDossiersDeUO = async (uoId: number, parentId?: number | null): Promise<DossierDto[]> => {
    try {
        const response = await api.get(`/user/dossiers/uo/${uoId}`, {
            params: parentId != null ? { parentId } : {},
        });
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur chargement des dossiers'
        );
    }
};

/** Nœud minimal de l'arbre complet des dossiers d'une UO — voir getArbreDossiers. */
export interface DossierArbreNodeDto {
    id: number;
    nom: string;
    /** null = dossier racine de l'UO. */
    parentId: number | null;
}

/**
 * GET /api/user/dossiers/uo/{uoId}/arbre — arbre COMPLET des dossiers de l'UO,
 * à plat, en un seul aller-retour (contrairement à getDossiersDeUO, scopé par
 * niveau) — pour un sélecteur pliable/dépliable côté client (voir
 * DossierTreePicker), typiquement le choix d'un dossier cible à l'archivage.
 */
export const getArbreDossiers = async (uoId: number): Promise<DossierArbreNodeDto[]> => {
    try {
        const response = await api.get(`/user/dossiers/uo/${uoId}/arbre`);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? "Erreur chargement de l'arbre des dossiers"
        );
    }
};

/**
 * GET /api/user/dossiers/{id} — lecture seule, voir getDossiersDeUO.
 */
export const getDossierDetail = async (id: number): Promise<DossierDetailDto> => {
    try {
        const response = await api.get(`/user/dossiers/${id}`);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur chargement du dossier'
        );
    }
};

/**
 * POST /api/editor/dossiers/{id}/types — additif.
 */
export const ajouterTypesAttendus = async (id: number, typeDocumentIds: number[]): Promise<DossierDto> => {
    try {
        const response = await api.post(`/editor/dossiers/${id}/types`, typeDocumentIds);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? "Erreur ajout des types attendus"
        );
    }
};

/**
 * DELETE /api/editor/dossiers/{id}/types/{typeId} — refusé si des documents de
 * ce type existent déjà dans ce dossier précis. Réservé aux éditeurs de l'UO.
 */
export const retirerTypeAttendu = async (id: number, typeId: number): Promise<DossierDto> => {
    try {
        const response = await api.delete(`/editor/dossiers/${id}/types/${typeId}`);
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? "Erreur retrait du type attendu"
        );
    }
};

/**
 * DELETE /api/editor/dossiers/{id} — uniquement si vide, réservé au créateur du dossier.
 */
export const supprimerDossier = async (id: number): Promise<void> => {
    try {
        await api.delete(`/editor/dossiers/${id}`);
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur suppression du dossier'
        );
    }
};

/**
 * Aperçu (dry-run) d'un déplacement — voir DossierDeplacementPreviewDto côté
 * serveur. À appeler AVANT deplacerDossier pour savoir s'il faut afficher un
 * modal d'alerte (le dossier va devenir privé, ou son groupe diverge de
 * celui du nouveau parent). nouveauParentId absent/null = aperçu d'un
 * déplacement vers la racine de l'UO.
 */
export interface DossierDeplacementPreviewDto {
    deviendraPrive: boolean;
    divergenceGroupes: boolean;
    membresDivergents: UserSummaryDto[];
}

export const previsualiserDeplacement = async (
    id: number, nouveauParentId?: number | null
): Promise<DossierDeplacementPreviewDto> => {
    try {
        const response = await api.get(`/editor/dossiers/${id}/deplacer/previsualiser`, {
            params: nouveauParentId != null ? { nouveauParentId } : {},
        });
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? "Erreur lors de l'aperçu du déplacement"
        );
    }
};

/**
 * PUT /api/editor/dossiers/{id}/deplacer — glisser-déposer, voir
 * DossierService.deplacerDossier côté serveur. nouveauParentId absent/null =
 * déplace vers la racine de l'UO.
 */
export const deplacerDossier = async (
    id: number, nouveauParentId: number | null
): Promise<DossierDto> => {
    try {
        const response = await api.put(`/editor/dossiers/${id}/deplacer`, { nouveauParentId });
        return response.data;
    } catch (error: any) {
        throw new Error(
            error.response?.data?.message ?? error.message ?? 'Erreur lors du déplacement du dossier'
        );
    }
};
