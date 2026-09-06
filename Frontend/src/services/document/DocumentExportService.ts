// services/document/DocumentExportService.ts
//
// Export administratif de documents d'une ou plusieurs UO — voir
// made.archive.service.document.DocumentExportService côté serveur.
// Pensé pour une migration ou un changement de système d'archivage, pas un
// usage quotidien : génère un ZIP (documents déchiffrés + manifest.csv) sur
// le disque du serveur, purgé automatiquement après 48h.
import api from '../api';

export interface ExportApercuRequestDto {
    uoIds: number[];
    excludeCorbeille: boolean;
    /** Réservé ADMIN — voir includePriveNonMembre côté serveur. */
    includePriveNonMembre: boolean;
}

export interface ExportLancerRequestDto {
    uoIds: number[];
    /** Optionnel : restreint le périmètre UO à ces documents précis. */
    docIds?: string[];
    separateProjects: boolean;
    excludeCorbeille: boolean;
    includePriveNonMembre: boolean;
    /** Obligatoire si includePriveNonMembre=true. */
    motif?: string;
}

export interface ExportApercuDocumentDto {
    id: string;
    titre: string;
    uoNom: string | null;
    typeDocumentNom: string | null;
    projetNom: string | null;
    access: 'PUBLIC' | 'PRIVE';
    /** Faux si ce document n'apparaît ici que grâce à includePriveNonMembre. */
    accesNormal: boolean;
}

export type ExportJobStatus = 'EN_ATTENTE' | 'EN_COURS' | 'PRET' | 'ECHEC';

export interface ExportJobStatutDto {
    id: string;
    statut: ExportJobStatus;
    documentsTotal: number;
    documentsTraites: number;
    documentsEnEchec: number;
    createAt: string;
    completedAt: string | null;
    expireAt: string;
}

/**
 * Les endpoints d'export renvoient le message d'erreur en corps BRUT
 * (`ResponseEntity.badRequest().body(e.getMessage())`, une String, pas un
 * objet { message }) — contrairement à la plupart des autres contrôleurs de
 * cette app. Gère les deux formes pour ne jamais afficher "[object Object]".
 */
function extraireErreur(error: any, repli: string): Error {
    const data = error?.response?.data;
    if (typeof data === 'string' && data.trim()) return new Error(data);
    if (data?.message) return new Error(data.message);
    return new Error(error?.message ?? repli);
}

/**
 * POST /api/admin_uo/document-export/apercu
 * Liste les documents du périmètre, sans rien générer.
 */
export const apercuExport = async (requete: ExportApercuRequestDto): Promise<ExportApercuDocumentDto[]> => {
    try {
        const response = await api.post('/admin_uo/document-export/apercu', requete);
        return response.data;
    } catch (error: any) {
        throw extraireErreur(error, "Erreur lors de l'aperçu de l'export");
    }
};

/**
 * POST /api/admin_uo/document-export/lancer
 * Démarre la génération en tâche de fond — à interroger via getStatutExport.
 */
export const lancerExport = async (requete: ExportLancerRequestDto): Promise<ExportJobStatutDto> => {
    try {
        const response = await api.post('/admin_uo/document-export/lancer', requete);
        return response.data;
    } catch (error: any) {
        throw extraireErreur(error, "Erreur lors du lancement de l'export");
    }
};

/**
 * GET /api/admin_uo/document-export/{jobId}/statut
 */
export const getStatutExport = async (jobId: string): Promise<ExportJobStatutDto> => {
    try {
        const response = await api.get(`/admin_uo/document-export/${jobId}/statut`);
        return response.data;
    } catch (error: any) {
        throw extraireErreur(error, 'Erreur lors de la consultation du statut');
    }
};

/**
 * GET /api/admin_uo/document-export/{jobId}/telecharger
 * Déclenche le téléchargement du ZIP une fois le job PRET.
 */
export const telechargerExport = async (jobId: string): Promise<void> => {
    try {
        const response = await api.get(`/admin_uo/document-export/${jobId}/telecharger`, {
            responseType: 'blob',
        });
        const url = URL.createObjectURL(response.data);
        const link = document.createElement('a');
        link.href = url;
        link.download = `export_${jobId}.zip`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        setTimeout(() => URL.revokeObjectURL(url), 10_000);
    } catch (error: any) {
        throw extraireErreur(error, 'Erreur lors du téléchargement de l\'export');
    }
};
