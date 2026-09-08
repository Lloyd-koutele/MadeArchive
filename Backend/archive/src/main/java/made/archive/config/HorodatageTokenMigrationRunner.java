package made.archive.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Migration ponctuelle documents.horodatage_token : oid (Large Object
 * Postgres) → bytea.
 *
 * Hibernate mappe un byte[] annoté @Lob en "oid" sur le dialecte Postgres,
 * pas en "bytea" inline (voir l'ancienne annotation de
 * Document.horodatageToken, désormais @JdbcTypeCode(VARBINARY)) — constaté
 * en conditions réelles : colonne illisible telle quelle en SQL (juste un
 * numéro de référence vers la table système pg_largeobject), lecture
 * exigeant une transaction explicite (voir DocumentExportRow), et surtout
 * jamais libérée automatiquement par Postgres à la suppression d'un
 * document — fuite silencieuse dans pg_largeobject (1 Large Object déjà
 * orphelin trouvé en base avant ce correctif). Un jeton RFC 3161 fait
 * quelques Ko : bytea, sans les contraintes des Large Objects, est le bon
 * choix.
 *
 * PAS dans schema.sql : c'est un bloc PL/pgSQL (DO $$ ... $$) contenant des
 * ";" internes (DECLARE, boucles), et Spring découpe schema.sql en
 * statements sur chaque ";" sans comprendre le dollar-quoting Postgres — un
 * DO block y est tronqué au premier ";" rencontré ("Unterminated dollar
 * quote", constaté en conditions réelles). Exécuté ici via
 * JdbcTemplate.execute(String), qui envoie la chaîne complète en un seul
 * appel JDBC, jamais reparsée par Spring.
 *
 * Un CommandLineRunner s'exécute après le rafraîchissement complet du
 * contexte, donc après schema.sql (spring.jpa.defer-datasource-initialization
 * =true — schema.sql lui-même tourne déjà après le DDL automatique
 * d'Hibernate) : la table documents existe forcément déjà à ce stade.
 *
 * Idempotent, sûr à rejouer à chaque démarrage : ne fait rien si la colonne
 * est déjà en bytea (cas de tout démarrage suivant celui qui migre, et de
 * toute base fraîchement créée où Hibernate lit directement la nouvelle
 * annotation). Vérifié en conditions réelles sur une copie de la base de
 * production (pg_dump -b / pg_restore) avant exécution ici : jetons
 * préservés bit à bit, Large Objects (dont l'orphelin) libérés, ré-exécution
 * sans effet.
 *
 * @Order(1) — s'exécute AVANT ExportJobJsonMigrationRunner et
 * LargeObjectOrphanCleanupRunner : le nettoyage final des Large Objects
 * orphelins (dans ce dernier) suppose que toutes les colonnes qui en
 * créaient encore ont déjà été converties par les migrations précédentes ;
 * ne libère ici QUE les Large Objects de documents.horodatage_token lui-même,
 * jamais un balayage global (voir LargeObjectOrphanCleanupRunner pour ça).
 */
@Order(1)
@Component
public class HorodatageTokenMigrationRunner implements CommandLineRunner
{
    private static final Logger logger = LoggerFactory.getLogger(HorodatageTokenMigrationRunner.class);

    private static final String MIGRATION_SQL = """
        DO $$
        DECLARE
            v_oid oid;
        BEGIN
            IF EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_name = 'documents' AND column_name = 'horodatage_token' AND data_type = 'oid'
            ) THEN
                ALTER TABLE documents ADD COLUMN horodatage_token_bytea bytea;

                UPDATE documents SET horodatage_token_bytea = lo_get(horodatage_token)
                WHERE horodatage_token IS NOT NULL;

                FOR v_oid IN SELECT DISTINCT horodatage_token FROM documents WHERE horodatage_token IS NOT NULL LOOP
                    PERFORM lo_unlink(v_oid);
                END LOOP;

                ALTER TABLE documents DROP COLUMN horodatage_token;
                ALTER TABLE documents RENAME COLUMN horodatage_token_bytea TO horodatage_token;
            END IF;
        END $$;
        """;

    private final JdbcTemplate jdbcTemplate;

    public HorodatageTokenMigrationRunner(JdbcTemplate jdbcTemplate)
    {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args)
    {
        try
        {
            jdbcTemplate.execute(MIGRATION_SQL);
            logger.info("[Migration] documents.horodatage_token (oid → bytea) : OK "
                + "(déjà migré si aucune action nécessaire).");
        }
        catch (Exception e)
        {
            logger.error("[Migration] Échec de la migration documents.horodatage_token (oid → bytea) : {}",
                e.getMessage(), e);
        }
    }
}
