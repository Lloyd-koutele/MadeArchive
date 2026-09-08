package made.archive.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Migration ponctuelle export_jobs.uo_ids_json / .document_ids_json : oid
 * (Large Object Postgres) → text.
 *
 * Même défaut que documents.horodatage_token (voir HorodatageTokenMigrationRunner
 * et Document.horodatageToken), mais côté String plutôt que byte[] : ces deux
 * champs étaient annotés @Lob (voir l'ancienne annotation d'ExportJob), et
 * Hibernate mappe une String @Lob en "oid" sur le dialecte Postgres, pas en
 * "text" inline — constaté en conditions réelles (colonnes uo_ids_json /
 * document_ids_json en oid). Ces deux colonnes stockent une liste d'IDs
 * sérialisée en JSON (voir ExportJob) : quelques centaines d'octets au plus
 * dans l'immense majorité des cas, jamais un vrai "gros" contenu — le mapping
 * correct, celui déjà utilisé par TypeDocument.extractionRegexJson, est un
 * simple @Column(columnDefinition = "TEXT") sans @Lob.
 *
 * @Order(2) — après HorodatageTokenMigrationRunner (documents.horodatage_token
 * doit déjà être migré) et avant LargeObjectOrphanCleanupRunner (qui suppose
 * que plus aucune colonne de l'application ne référence de Large Object une
 * fois cette migration terminée).
 *
 * Idempotent, sûr à rejouer à chaque démarrage : ne fait rien si les colonnes
 * sont déjà en text.
 */
@Order(2)
@Component
public class ExportJobJsonMigrationRunner implements CommandLineRunner
{
    private static final Logger logger = LoggerFactory.getLogger(ExportJobJsonMigrationRunner.class);

    private static final String MIGRATION_SQL = """
        DO $$
        DECLARE
            v_oid oid;
        BEGIN
            IF EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_name = 'export_jobs' AND column_name = 'uo_ids_json' AND data_type = 'oid'
            ) THEN
                ALTER TABLE export_jobs ADD COLUMN uo_ids_json_text text;
                ALTER TABLE export_jobs ADD COLUMN document_ids_json_text text;

                UPDATE export_jobs SET uo_ids_json_text = convert_from(lo_get(uo_ids_json), 'UTF8')
                WHERE uo_ids_json IS NOT NULL;
                UPDATE export_jobs SET document_ids_json_text = convert_from(lo_get(document_ids_json), 'UTF8')
                WHERE document_ids_json IS NOT NULL;

                FOR v_oid IN SELECT DISTINCT uo_ids_json FROM export_jobs WHERE uo_ids_json IS NOT NULL LOOP
                    PERFORM lo_unlink(v_oid);
                END LOOP;
                FOR v_oid IN SELECT DISTINCT document_ids_json FROM export_jobs WHERE document_ids_json IS NOT NULL LOOP
                    PERFORM lo_unlink(v_oid);
                END LOOP;

                ALTER TABLE export_jobs DROP COLUMN uo_ids_json;
                ALTER TABLE export_jobs DROP COLUMN document_ids_json;
                ALTER TABLE export_jobs RENAME COLUMN uo_ids_json_text TO uo_ids_json;
                ALTER TABLE export_jobs RENAME COLUMN document_ids_json_text TO document_ids_json;

                -- uo_ids_json est NOT NULL sur l'entité (nullable = false) —
                -- reposée après coup, une fois la colonne repeuplée.
                ALTER TABLE export_jobs ALTER COLUMN uo_ids_json SET NOT NULL;
            END IF;
        END $$;
        """;

    private final JdbcTemplate jdbcTemplate;

    public ExportJobJsonMigrationRunner(JdbcTemplate jdbcTemplate)
    {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args)
    {
        try
        {
            jdbcTemplate.execute(MIGRATION_SQL);
            logger.info("[Migration] export_jobs.uo_ids_json/document_ids_json (oid → text) : OK "
                + "(déjà migré si aucune action nécessaire).");
        }
        catch (Exception e)
        {
            logger.error("[Migration] Échec de la migration export_jobs.uo_ids_json/document_ids_json "
                + "(oid → text) : {}", e.getMessage(), e);
        }
    }
}
