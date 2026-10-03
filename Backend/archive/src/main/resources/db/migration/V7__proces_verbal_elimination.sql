-- Procès-verbal d'élimination automatique (voir service.document.ProcesVerbalEliminationService).
--
--   elimine_le / elimine_par : date et auteur (null = le système) de la purge — posés par purgeOne,
--     conservés dans la pierre tombale. Seuls les documents éliminés APRÈS cette migration en ont un
--     (donc seuls eux reçoivent un procès-verbal).
--   proces_verbal_id : le procès-verbal (document archivé dans MadeArchive) qui mentionne cette
--     élimination — null tant qu'il n'est pas généré (réessayé chaque nuit).
--   type_documents.systeme : type créé par l'application elle-même (ex. "Procès-verbal d'élimination") —
--     ni modifiable, ni supprimable, ni reclassable par un utilisateur.

ALTER TABLE documents ADD COLUMN IF NOT EXISTS elimine_le       timestamptz;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS elimine_par      uuid;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS proces_verbal_id uuid REFERENCES documents (id);
ALTER TABLE type_documents ADD COLUMN IF NOT EXISTS systeme boolean NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_documents_pv_en_attente ON documents (status) WHERE elimine_le IS NOT NULL AND proces_verbal_id IS NULL;
