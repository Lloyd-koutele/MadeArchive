package made.archive.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Nettoyage final des Large Objects Postgres devenus orphelins.
 *
 * Après HorodatageTokenMigrationRunner (@Order(1)) et ExportJobJsonMigrationRunner
 * (@Order(2)), plus AUCUNE colonne de cette application ne crée de Large
 * Object (grep @Lob sur tout le code : plus aucune occurrence réelle, juste
 * ces commentaires historiques) — documents.horodatage_token est en bytea,
 * export_jobs.uo_ids_json/.document_ids_json sont en text. Tout Large Object
 * encore présent dans pg_largeobject à ce stade est donc, sans exception,
 * orphelin : soit un reliquat des deux migrations ci-dessus (déjà copié
 * ailleurs, jamais libéré si un redémarrage a interrompu la migration entre
 * la copie et l'unlink), soit un Large Object plus ancien, jamais libéré par
 * Postgres (qui ne le fait jamais automatiquement à la suppression de la
 * ligne qui le référençait — voir HorodatageTokenMigrationRunner) — un
 * exemplaire de ce cas précis a été trouvé et confirmé en base avant ce
 * correctif.
 *
 * @Order(3) — DOIT s'exécuter en dernier. Si une future colonne de
 * l'application recommence à utiliser @Lob, ce nettoyage la videra sans
 * prévenir : ne pas la réintroduire sans revoir ce runner en conséquence.
 *
 * Idempotent, sûr à rejouer à chaque démarrage : ne fait rien si
 * pg_largeobject_metadata est déjà vide (cas normal après le premier
 * nettoyage).
 */
@Order(3)
@Component
public class LargeObjectOrphanCleanupRunner implements CommandLineRunner
{
    private static final Logger logger = LoggerFactory.getLogger(LargeObjectOrphanCleanupRunner.class);

    private static final String CLEANUP_SQL = """
        DO $$
        DECLARE
            v_oid oid;
            v_count integer := 0;
        BEGIN
            FOR v_oid IN SELECT oid FROM pg_largeobject_metadata LOOP
                PERFORM lo_unlink(v_oid);
                v_count := v_count + 1;
            END LOOP;
            RAISE NOTICE 'large_objects_unlinked=%', v_count;
        END $$;
        """;

    private final JdbcTemplate jdbcTemplate;

    public LargeObjectOrphanCleanupRunner(JdbcTemplate jdbcTemplate)
    {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args)
    {
        try
        {
            Integer restants = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_largeobject_metadata", Integer.class);

            if (restants != null && restants > 0)
            {
                jdbcTemplate.execute(CLEANUP_SQL);
                logger.info("[Migration] Nettoyage Large Objects orphelins : {} objet(s) libéré(s).", restants);
            }
        }
        catch (Exception e)
        {
            logger.error("[Migration] Échec du nettoyage des Large Objects orphelins : {}", e.getMessage(), e);
        }
    }
}
