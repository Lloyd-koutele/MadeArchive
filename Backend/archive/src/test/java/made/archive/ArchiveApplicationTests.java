package made.archive;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Démarre l'application COMPLÈTE (tous les beans — MinIO, Redis, Meilisearch,
 * Ollama, HSM, secrets JWT...) : nécessite un environnement réel, bien plus
 * lourd que la seule base PostgreSQL éphémère des tests d'intégration ciblés
 * (voir made.archive.integration.DocumentExportIntegrationTest, tag
 * "integration"). Tag DISTINCT délibéré ("full-context") : ce test échoue
 * systématiquement hors d'un environnement doté d'un .env complet — y
 * compris en CI tant qu'un job dédié (docker-compose + secrets) ne lui est
 * pas construit, ce qui n'a pas été fait dans cette passe. Il ne tourne donc
 * ni dans './gradlew test' ni dans './gradlew integrationTest' — exclu
 * explicitement plutôt que silencieusement rouge.
 */
@Tag("full-context")
@SpringBootTest
class ArchiveApplicationTests {

	@Test
	void contextLoads() {
	}

}
