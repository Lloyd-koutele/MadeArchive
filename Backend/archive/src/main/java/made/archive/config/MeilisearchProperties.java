package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "meilisearch")
public class MeilisearchProperties 
{
    private String host;
    private String apiKey;
    private String searchKey;
}