-- Rotation des jetons de rafraîchissement et durée maximale de session.
--   famille_id          : relie les jetons successifs d'une même connexion (révocation en bloc si un jeton
--                         déjà échangé est présenté de nouveau : copie volée ou rejeu).
--   expiration_absolue  : fin de vie maximale de la connexion, que les renouvellements ne repoussent jamais.
--   cree_le             : création de chaque jeton.
--   rotated_at/successeur : jeton échangé, et celui qui l'a remplacé.
-- Idempotent ; les sessions existantes deviennent leur propre famille.

ALTER TABLE device_sessions
    ADD COLUMN IF NOT EXISTS famille_id varchar(64),
    ADD COLUMN IF NOT EXISTS expiration_absolue timestamptz,
    ADD COLUMN IF NOT EXISTS cree_le timestamptz,
    ADD COLUMN IF NOT EXISTS rotated_at timestamptz,
    ADD COLUMN IF NOT EXISTS successeur varchar(255);

UPDATE device_sessions
SET famille_id = refresh_token,
    expiration_absolue = expires_at,
    cree_le = now()
WHERE famille_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_device_sessions_famille ON device_sessions (famille_id);
