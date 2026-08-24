// services/admin/AuditLogService.ts
import api from '../api';

/**
 * Catalogue miroir de made.archive.entite.AuditAction (backend). Tenu à jour
 * manuellement — pas de génération automatique dans ce projet.
 */
export type AuditAction =
    | 'LOGIN_REUSSI' | 'LOGIN_ECHOUE' | 'LOGOUT' | 'TOKEN_RAFRAICHI' | 'SESSION_INVALIDEE'
    | 'UTILISATEUR_CREE' | 'UTILISATEUR_MODIFIE' | 'UTILISATEUR_BLOQUE' | 'UTILISATEUR_REACTIVE' | 'PROFIL_MODIFIE'
    | 'UO_CREEE' | 'UO_MODIFIEE' | 'UO_SUPPRIMEE' | 'UO_RACINE_CHANGEE'
    | 'UO_MEMBRE_AJOUTE' | 'UO_MEMBRE_RETIRE' | 'UO_MEMBRE_TRANSFERE'
    | 'DOCUMENT_UPLOAD_REUSSI' | 'DOCUMENT_UPLOAD_ECHOUE' | 'DOCUMENT_NOUVELLE_VERSION' | 'DOCUMENT_CORRUPTION_DETECTEE'
    | 'DOCUMENT_CONSULTE' | 'DOCUMENT_TELECHARGE' | 'DOCUMENT_RECHERCHE' | 'DOCUMENT_VERIFICATION_PUBLIQUE'
    | 'GROUPE_MEMBRE_AJOUTE' | 'GROUPE_MEMBRE_RETIRE'
    | 'TYPE_DOCUMENT_CREE' | 'TYPE_DOCUMENT_MODIFIE' | 'TYPE_DOCUMENT_REGEX_REINITIALISEE' | 'TYPE_DOCUMENT_SUPPRIME'
    | 'PROJET_CREE' | 'PROJET_TYPES_AJOUTES' | 'PROJET_SUPPRIME';

export type AuditCible =
    | 'SESSION' | 'UTILISATEUR' | 'UNITE_ORGANISATIONNELLE' | 'DOCUMENT'
    | 'GROUPE_ACCES' | 'TYPE_DOCUMENT' | 'PROJET';

export interface AuditLogDto {
    id: number;
    horodatage: string;
    acteurId: string | null;
    acteurEmail: string | null;
    acteurRole: string | null;
    adresseIp: string | null;
    action: AuditAction;
    cibleType: AuditCible | null;
    cibleId: string | null;
    uoId: number | null;
    description: string;
    succes: boolean;
    details: string | null;
}

export interface AuditLogPageDto {
    content: AuditLogDto[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

export interface AuditLogFiltre {
    acteurId?: string;
    action?: AuditAction;
    cibleType?: AuditCible;
    dateDebut?: string; // ISO 8601
    dateFin?: string;   // ISO 8601
    texte?: string;
    page?: number;
    size?: number;
}

/** Construit la query string commune à la recherche paginée et à l'export (mêmes filtres). */
const construireParams = (filtre: AuditLogFiltre): URLSearchParams => {
    const params = new URLSearchParams();
    if (filtre.acteurId) params.append('acteurId', filtre.acteurId);
    if (filtre.action) params.append('action', filtre.action);
    if (filtre.cibleType) params.append('cibleType', filtre.cibleType);
    if (filtre.dateDebut) params.append('dateDebut', filtre.dateDebut);
    if (filtre.dateFin) params.append('dateFin', filtre.dateFin);
    if (filtre.texte) params.append('texte', filtre.texte);
    return params;
};

/**
 * GET /api/admin_uo/audit-logs — ADMIN voit tout, ADMIN_UO est automatiquement
 * restreint à son UO + sous-arbre côté serveur (aucun paramètre ne permet de
 * contourner cette restriction).
 */
export const rechercherAuditLogs = async (filtre: AuditLogFiltre = {}): Promise<AuditLogPageDto> => {
    try {
        const params = construireParams(filtre);
        params.append('page', String(filtre.page ?? 0));
        params.append('size', String(filtre.size ?? 25));

        const response = await api.get(`/admin_uo/audit-logs?${params.toString()}`);
        return response.data;
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

export type AuditLogExportFormat = 'csv' | 'log';

/**
 * GET /api/admin_uo/audit-logs/export — télécharge exactement les entrées que les
 * filtres actifs sélectionnent (mêmes filtres que rechercherAuditLogs, sans pagination),
 * dans le format demandé. Le scoping ADMIN_UO s'applique de la même façon.
 */
export const exporterAuditLogs = async (
    filtre: AuditLogFiltre,
    format: AuditLogExportFormat,
): Promise<void> => {
    try {
        const params = construireParams(filtre);
        params.append('format', format);

        const response = await api.get(`/admin_uo/audit-logs/export?${params.toString()}`, {
            responseType: 'blob',
        });

        const date = new Date().toISOString().slice(0, 10);
        const nomFichier = `journal-audit_${date}.${format}`;
        triggerDownload(response.data, nomFichier);
    } catch (error: any) {
        throw error.response?.data?.message
            ? new Error(error.response.data.message)
            : error;
    }
};

function triggerDownload(blob: Blob, filename: string): void {
    const url  = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href     = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
}
