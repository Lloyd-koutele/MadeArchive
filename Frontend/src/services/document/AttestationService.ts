import api from "../api";

// ═══════════════════════════════════════════════════════════════════════════
// Attestation d'archivage — voir Backend AttestationService/AttestationDto
// ═══════════════════════════════════════════════════════════════════════════

export interface AttestationDto {
    token: string;
    /** Lien public complet (frontend, /attestation/:token) — encodé dans le QR du PDF. */
    url: string;
    /** true si une attestation existait déjà pour ce document (jeton réutilisé). */
    dejaExistante: boolean;
}

/**
 * POST /api/user/docs/{id}/attestation
 * Génère (ou récupère si déjà générée) l'attestation d'archivage d'un
 * document — réservé à qui a normalement accès au document.
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
