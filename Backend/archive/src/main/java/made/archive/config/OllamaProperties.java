package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Ollama local — désormais OPTIONNEL (voir OllamaService) : baseUrl vide par
 * défaut (OLLAMA_BASE_URL absente de .env), pas une erreur au démarrage.
 * Alternative/complément : ExternalLlmProperties (API LLM externe générique).
 */
@Data
@Component
@ConfigurationProperties(prefix = "ollama")
public class OllamaProperties
{
    private String baseUrl;
    private String model;

    public boolean estConfiguree()
    {
        return StringUtils.hasText(baseUrl);
    }
}
