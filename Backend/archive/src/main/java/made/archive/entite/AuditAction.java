package made.archive.entite;

/**
 * Catalogue des actions journalisées dans le journal d'audit (voir JournalAudit).
 * Volontairement limité aux actions qui changent un état ou donnent accès à une
 * information sensible — pas les simples listages/consultations de référentiels.
 */
public enum AuditAction
{
    // ── Authentification & session ──────────────────────────────────────────
    LOGIN_REUSSI,
    LOGIN_ECHOUE,
    LOGOUT,
    TOKEN_RAFRAICHI,
    SESSION_INVALIDEE,

    // ── Comptes utilisateurs ─────────────────────────────────────────────────
    UTILISATEUR_CREE,
    UTILISATEUR_MODIFIE,
    UTILISATEUR_BLOQUE,
    UTILISATEUR_REACTIVE,
    PROFIL_MODIFIE,

    // ── Organisation (UO) ────────────────────────────────────────────────────
    UO_CREEE,
    UO_MODIFIEE,
    UO_SUPPRIMEE,
    UO_RACINE_CHANGEE,
    UO_MEMBRE_AJOUTE,
    UO_MEMBRE_RETIRE,
    UO_MEMBRE_TRANSFERE,

    // ── Documents — cycle de vie ─────────────────────────────────────────────
    DOCUMENT_UPLOAD_REUSSI,
    DOCUMENT_UPLOAD_ECHOUE,
    DOCUMENT_NOUVELLE_VERSION,
    DOCUMENT_CORRUPTION_DETECTEE,
    DOCUMENT_SUPPRESSION_PLANIFIEE,
    DOCUMENT_SUPPRIME_DEFINITIVEMENT,

    // ── Documents — consultation ─────────────────────────────────────────────
    DOCUMENT_CONSULTE,
    DOCUMENT_TELECHARGE,
    DOCUMENT_RECHERCHE,
    DOCUMENT_VERIFICATION_PUBLIQUE,

    // ── Groupes d'accès ──────────────────────────────────────────────────────
    GROUPE_MEMBRE_AJOUTE,
    GROUPE_MEMBRE_RETIRE,

    // ── Types de documents ───────────────────────────────────────────────────
    TYPE_DOCUMENT_CREE,
    TYPE_DOCUMENT_MODIFIE,
    TYPE_DOCUMENT_REGEX_REINITIALISEE,
    TYPE_DOCUMENT_SUPPRIME,

    // ── Projets ───────────────────────────────────────────────────────────────
    PROJET_CREE,
    PROJET_TYPES_AJOUTES,
    PROJET_TYPE_RETIRE,
    PROJET_SUPPRIME,

    // ── Attestations d'archivage ─────────────────────────────────────────────
    ATTESTATION_GENEREE,
    ATTESTATION_CONSULTEE_PUBLIQUEMENT,

    // ── Localisation physique ─────────────────────────────────────────────────
    LOCATION_CREEE,
    LOCATION_MODIFIEE,
    LOCATION_TYPE_CHANGE,
    LOCATION_DESACTIVEE,
    LOCATION_REACTIVEE,
    LOCATION_SUPPRIMEE,
    DOCUMENT_EMPLACEMENT_MODIFIE
}
