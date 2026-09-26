import api from "../api";

// ═══════════════════════════════════════════════════════════════════════════
// Attestation d'archivage — voir Backend AttestationService/AttestationDto
// ═══════════════════════════════════════════════════════════════════════════

export interface AttestationDto {
    token: string;
    /**
     * Lien public complet vers CETTE attestation (frontend, /attestation/:token)
     * — utilisé par le bouton "Ouvrir" juste après génération côté éditeur.
     * PAS le lien encodé dans le QR du PDF : celui-ci mène au document
     * original (/attestation/:token/document, voir getAttestationDocumentViewUrl
     * ci-dessous et AttestationService.genererPdfPourToken côté serveur).
     */
    url: string;
}

/**
 * POST /api/user/docs/{id}/attestation
 * Génère une NOUVELLE attestation d'archivage d'un document (jeton
 * indépendant, pas de réutilisation — un document peut en avoir plusieurs
 * actives à la fois) — réservé à qui a normalement accès au document.
 */
export const genererAttestation = async (documentId: string): Promise<AttestationDto> => {
    const response = await api.post<AttestationDto>(`/user/docs/${documentId}/attestation`);
    return response.data;
};

/** URL backend directe du PDF (visionneuse) — endpoint public, pas d'auth. */
export const getAttestationViewUrl = (token: string): string =>
    `${api.defaults.baseURL}/public/attestation/${token}/view`;

/** URL backend directe du PDF (téléchargement) — endpoint public, pas d'auth. */
export const getAttestationDownloadUrl = (token: string): string =>
    `${api.defaults.baseURL}/public/attestation/${token}/download`;

/**
 * URL backend directe du DOCUMENT ORIGINAL (visionneuse) — endpoint public,
 * pas d'auth, accessible même si le document est PRIVÉ (voir Javadoc
 * AttestationPublicController côté serveur). C'est CE lien qui est encodé
 * dans le QR imprimé sur l'attestation, pas getAttestationViewUrl ci-dessus.
 * CONSULTATION UNIQUEMENT depuis cette source (revu le 09/2026) : pas
 * d'équivalent "download" pour le document original, contrairement à
 * l'attestation elle-même (getAttestationDownloadUrl ci-dessus).
 */
export const getAttestationDocumentViewUrl = (token: string): string =>
    `${api.defaults.baseURL}/public/attestation/${token}/document/view`;
