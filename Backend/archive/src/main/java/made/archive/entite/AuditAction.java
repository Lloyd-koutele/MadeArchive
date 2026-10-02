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
    /** Levée manuelle, par un administrateur, du blocage anti-bruteforce d'un
     *  compte (voir LoginAttemptService.debloquerAdmin) — distinct d'un simple
     *  changement de mot de passe, qui ne lève jamais ce blocage à lui seul. */
    CONNEXION_DEVERROUILLEE,

    // ── Comptes utilisateurs ─────────────────────────────────────────────────
    UTILISATEUR_CREE,
    UTILISATEUR_MODIFIE,
    UTILISATEUR_BLOQUE,
    UTILISATEUR_REACTIVE,
    /** Suppression demandée — n'exécute rien tout de suite, voir UTILISATEUR_SUPPRIME. */
    UTILISATEUR_SUPPRESSION_DEMANDEE,
    UTILISATEUR_SUPPRESSION_ANNULEE,
    /** Suppression réellement EXÉCUTÉE, délai de grâce de 2 jours écoulé — voir
     *  UserSuppressionCleanupScheduler. */
    UTILISATEUR_SUPPRIME,
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
    /** @deprecated remplacé par DOCUMENT_PLACE_CORBEILLE (voir DocumentService.envoyerCorbeille)
     *  — conservé uniquement pour ne pas invalider les entrées déjà écrites dans le journal. */
    @Deprecated
    DOCUMENT_SUPPRESSION_PLANIFIEE,
    DOCUMENT_PLACE_CORBEILLE,
    DOCUMENT_RESTAURE_CORBEILLE,
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
    TYPE_DOCUMENT_REGEX_MODIFIEE,
    TYPE_DOCUMENT_SUPPRIME,

    // ── Dossiers ───────────────────────────────────────────────────────────────
    DOSSIER_CREE,
    DOSSIER_MODIFIE,
    DOSSIER_TYPES_AJOUTES,
    DOSSIER_TYPE_RETIRE,
    DOSSIER_SUPPRIME,
    /** Bascule PUBLIC ↔ PRIVÉ après coup — voir DossierService.modifierAcces. */
    DOSSIER_ACCES_MODIFIE,
    /** Glisser-déposer vers un nouveau parent — voir DossierService.deplacerDossier. */
    DOSSIER_DEPLACE,

    // ── Attestations d'archivage ─────────────────────────────────────────────
    ATTESTATION_GENEREE,
    ATTESTATION_CONSULTEE_PUBLIQUEMENT,
    /** Supprimée par expiration (2 jours) ou passage PUBLIC → PRIVÉ du document — voir AttestationService/DocumentService.modifierAcces. */
    ATTESTATION_PURGEE,

    // ── Localisation physique ─────────────────────────────────────────────────
    LOCATION_CREEE,
    LOCATION_MODIFIEE,
    LOCATION_TYPE_CHANGE,
    LOCATION_DESACTIVEE,
    LOCATION_REACTIVEE,
    LOCATION_SUPPRIMEE,
    LOCATION_DEPLACEE,
    DOCUMENT_EMPLACEMENT_MODIFIE,
    DOCUMENT_METADATA_MODIFIEE,
    DOCUMENT_DOSSIER_MODIFIE,
    /** Bascule PUBLIC ↔ PRIVÉ après coup — voir DocumentService.modifierAcces. */
    DOCUMENT_ACCES_MODIFIE,

    // ── Export administratif de documents (migration/changement de système) ───
    /** Export demandé — le champ succes distingue une élévation vers des
     *  documents privés non-membres (voir EXPORT_DOCUMENTS_PRIVES_INCLUS,
     *  écrit EN PLUS de celle-ci quand includePriveNonMembre=true). */
    EXPORT_DOCUMENTS_DEMANDE,
    /** Marqueur distinct, volontairement séparé de EXPORT_DOCUMENTS_DEMANDE,
     *  pour qu'un export incluant des documents privés hors appartenance du
     *  demandeur saute aux yeux dans le journal plutôt que d'être noyé parmi
     *  les exports ordinaires — voir DocumentExportService. */
    EXPORT_DOCUMENTS_PRIVES_INCLUS,
    EXPORT_TELECHARGE,

    // ── Contrôle d'intégrité (fixity check) ───────────────────────────────────
    /** Vérification déclenchée manuellement (par opposition à la tâche planifiée
     *  quotidienne, voir FixityCheckScheduler) — journalise le périmètre demandé,
     *  pas chaque document vérifié individuellement (déjà trop de volume pour ça). */
    FIXITY_CHECK_DEMANDE,

    // ── Journal d'audit chaîné (voir service.audit.AuditChainService) ─────────
    /** Vérification de la chaîne déclenchée manuellement, à la demande — par
     *  opposition au chaînage/scellement nocturne automatique (AuditChainScheduler),
     *  qui n'est volontairement jamais journalisé lui-même (bruit quotidien
     *  inutile, même principe que FixityCheckScheduler). */
    CHAINE_AUDIT_VERIFICATION_DEMANDEE,

    // ── Sort final (voir entite.SortFinal) ─────────────────────────────────────
    TYPE_DOCUMENT_SORT_FINAL_MODIFIE,

    // ── Journal de cycle de vie d'un document (voir service.document.DocumentJournalService) ──
    /** Jeton d'horodatage RFC 3161 obtenu pour ce document (à l'archivage ou à la reprise différée). */
    DOCUMENT_HORODATE,
    /** Export (CSV/.log) du journal de CE document par un utilisateur. */
    DOCUMENT_JOURNAL_EXPORTE,

    // ── Plan de classement (voir entite.PlanClassementNoeud) ───────────────────
    PLAN_CLASSEMENT_NOEUD_CREE,
    PLAN_CLASSEMENT_NOEUD_MODIFIE,
    PLAN_CLASSEMENT_NOEUD_DEPLACE,
    PLAN_CLASSEMENT_NOEUD_SUPPRIME,
    /** Rattachement/changement/détachement de l'activité d'un type de document. */
    TYPE_DOCUMENT_ACTIVITE_MODIFIEE
}
