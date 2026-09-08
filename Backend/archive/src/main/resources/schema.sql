CREATE UNIQUE INDEX IF NOT EXISTS uk_membre_uo_user_actif
ON membres_uo (user_id)
WHERE actif = true;

-- fixity_check_results.checked_at : DATE → TIMESTAMPTZ. Hibernate ddl-auto=update
-- n'altère jamais le type d'une colonne EXISTANTE (seulement les ajouts) — sans
-- cette migration explicite, l'entité passée en Instant échouerait à l'écriture
-- ("column checked_at is of type date but expression is of type timestamp").
-- Idempotent : reconvertir une colonne déjà timestamptz est un no-op, sûr à
-- rejouer à chaque démarrage comme le reste de ce fichier. Nécessaire pour
-- distinguer "vérifié il y a 2h" de "vérifié il y a 20h" (dédoublonnage des
-- déclenchements manuels du contrôle d'intégrité, voir FixityCheckAsyncExecutor)
-- — une simple date ne le permettait pas.
ALTER TABLE fixity_check_results
    ALTER COLUMN checked_at TYPE timestamptz USING checked_at::timestamptz;

ALTER TABLE journal_audit DROP CONSTRAINT IF EXISTS journal_audit_action_check;
ALTER TABLE journal_audit ADD CONSTRAINT journal_audit_action_check CHECK (action::text = ANY (ARRAY[
    'LOGIN_REUSSI','LOGIN_ECHOUE','LOGOUT','TOKEN_RAFRAICHI','SESSION_INVALIDEE',
    'UTILISATEUR_CREE','UTILISATEUR_MODIFIE','UTILISATEUR_BLOQUE','UTILISATEUR_REACTIVE',
    'UTILISATEUR_SUPPRESSION_DEMANDEE','UTILISATEUR_SUPPRESSION_ANNULEE','UTILISATEUR_SUPPRIME','PROFIL_MODIFIE',
    'UO_CREEE','UO_MODIFIEE','UO_SUPPRIMEE','UO_RACINE_CHANGEE','UO_MEMBRE_AJOUTE','UO_MEMBRE_RETIRE','UO_MEMBRE_TRANSFERE',
    'DOCUMENT_UPLOAD_REUSSI','DOCUMENT_UPLOAD_ECHOUE','DOCUMENT_NOUVELLE_VERSION','DOCUMENT_CORRUPTION_DETECTEE',
    'DOCUMENT_SUPPRESSION_PLANIFIEE','DOCUMENT_PLACE_CORBEILLE','DOCUMENT_RESTAURE_CORBEILLE','DOCUMENT_SUPPRIME_DEFINITIVEMENT',
    'DOCUMENT_CONSULTE','DOCUMENT_TELECHARGE','DOCUMENT_RECHERCHE','DOCUMENT_VERIFICATION_PUBLIQUE',
    'GROUPE_MEMBRE_AJOUTE','GROUPE_MEMBRE_RETIRE',
    'TYPE_DOCUMENT_CREE','TYPE_DOCUMENT_MODIFIE','TYPE_DOCUMENT_REGEX_REINITIALISEE','TYPE_DOCUMENT_REGEX_MODIFIEE','TYPE_DOCUMENT_SUPPRIME',
    'PROJET_CREE','PROJET_MODIFIE','PROJET_TYPES_AJOUTES','PROJET_TYPE_RETIRE','PROJET_SUPPRIME',
    'ATTESTATION_GENEREE','ATTESTATION_CONSULTEE_PUBLIQUEMENT',
    'LOCATION_CREEE','LOCATION_MODIFIEE','LOCATION_TYPE_CHANGE','LOCATION_DESACTIVEE','LOCATION_REACTIVEE','LOCATION_SUPPRIMEE','LOCATION_DEPLACEE',
    'DOCUMENT_EMPLACEMENT_MODIFIE','DOCUMENT_METADATA_MODIFIEE','DOCUMENT_PROJET_MODIFIE',
    'EXPORT_DOCUMENTS_DEMANDE','EXPORT_DOCUMENTS_PRIVES_INCLUS','EXPORT_TELECHARGE',
    'FIXITY_CHECK_DEMANDE'
]::text[]));

ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_status_check;
ALTER TABLE documents ADD CONSTRAINT documents_status_check CHECK (status::text = ANY (ARRAY[
    'PENDING','ACTIVE','ACTIVE_WARNING','CORRUPTED','CORBEILLE','DELETED'
]::text[]));

ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_statut_avant_corbeille_check;
ALTER TABLE documents ADD CONSTRAINT documents_statut_avant_corbeille_check CHECK (statut_avant_corbeille IS NULL OR statut_avant_corbeille::text = ANY (ARRAY[
    'PENDING','ACTIVE','ACTIVE_WARNING','CORRUPTED','CORBEILLE','DELETED'
]::text[]));

-- notifications.type — même fragilité (contrainte CHECK générée par Hibernate
-- à partir de l'enum NotificationType), même mécanisme, même piège.
ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type::text = ANY (ARRAY[
    'DOCUMENT_CORROMPU','DOCUMENT_AJOUTE','PROJET_CREE','UO_CREEE',
    'DOCUMENT_HORODATAGE_ECHEC','DOCUMENT_HORODATAGE_REUSSI',
    'EXPORT_PRET','DOCUMENT_INCLUS_DANS_EXPORT','FIXITY_CHECK_TERMINE'
]::text[]));

ALTER TABLE groupe_access DROP COLUMN IF EXISTS nom;