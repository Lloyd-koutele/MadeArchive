-- Preuves d'intégrité ancrées hors de la table des documents seule — voir service.integrite.
--
--   signature_enregistrement(+_alias) : signature, par une clé du HSM (jamais lue en base), de
--     l'enregistrement canonique du document (identifiant, SHA-256 PDF/A et original, UO, version, date,
--     clé de stockage). Modifier l'un de ces champs sans la clé privée invalide la signature.
--   ancrage_id : lot d'ancrage (racine de Merkle horodatée) dans lequel le document est entré.
--   ancrages_catalogue : une ligne par ancrage quotidien — racine de Merkle de tous les nouveaux
--     documents, signée par la clé système et horodatée RFC 3161 par un tiers.

ALTER TABLE documents ADD COLUMN IF NOT EXISTS signature_enregistrement text;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS signature_enregistrement_alias varchar(100);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS ancrage_id bigint;

CREATE TABLE IF NOT EXISTS ancrages_catalogue (
    id                bigserial PRIMARY KEY,
    created_at        timestamptz  NOT NULL,
    nombre_documents  integer      NOT NULL,
    racine_merkle     varchar(64)  NOT NULL,
    racine_precedente varchar(64),
    signature         text         NOT NULL,
    signature_alias   varchar(100) NOT NULL,
    horodatage_token  bytea,
    horodatage_date   timestamptz,
    blockchain_tx     varchar(400)
);

CREATE INDEX IF NOT EXISTS idx_documents_ancrage_id ON documents (ancrage_id);

-- Rattrapage du scellement des documents existants : minuscule index partiel (seuls les non scellés).
CREATE INDEX IF NOT EXISTS idx_documents_a_sceller ON documents (id) WHERE signature_enregistrement IS NULL;
