-- Colonnes de l'entité PhysicalLocation (capacité, mode de contrainte, dossier ou type imposé) qui
-- n'avaient jamais reçu de migration : elles n'existaient que sur les bases créées avant le passage à
-- Flyway (ddl-auto=validate). Sur une installation NEUVE, l'application refusait de démarrer
-- (« Schema validation: missing column [capacite_max] »).
--
-- Idempotent : sans effet sur une base qui possède déjà ces colonnes.

ALTER TABLE physical_locations
    ADD COLUMN IF NOT EXISTS capacite_max integer,
    ADD COLUMN IF NOT EXISTS mode_contrainte varchar(20) NOT NULL DEFAULT 'LIBRE'
        CHECK (mode_contrainte IN ('LIBRE', 'TYPE_UNIQUE', 'DOSSIER')),
    ADD COLUMN IF NOT EXISTS dossier_id bigint REFERENCES dossiers (id),
    ADD COLUMN IF NOT EXISTS type_document_accepte_id bigint REFERENCES type_documents (id);
