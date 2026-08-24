package made.archive.config;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class MeilisearchConfig
{
    private final MeilisearchProperties props;

    /**
     * Client Meilisearch pour les opérations D'ÉCRITURE (suppression de documents
     * notamment — voir MeilisearchService.deleteDocument/deleteDocuments).
     * Doit utiliser la clé ADMIN (master key), pas la Search API Key : cette
     * dernière n'a que des droits de lecture côté Meilisearch et fait échouer
     * silencieusement toute opération d'écriture avec ce client.
     */
    @Bean
    public Client meilisearchClient()
    {
        return new Client(new Config(props.getHost(), props.getApiKey()));
    }
}