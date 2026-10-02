-- Format d'un export : ZIP + manifest.csv (existant) ou paquet SEDA 2.1 (manifest.xml).
-- DEFAULT explicite : backfill sûr des exports déjà enregistrés.
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS format varchar(20) NOT NULL DEFAULT 'ZIP_CSV';
