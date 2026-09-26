package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * API LLM externe générique, alternative/complément à Ollama local — voir
 * OllamaService pour l'ordre de priorité (externe d'abord si les deux sont
 * renseignées) et le format de requête attendu.
 *
 * Volontairement AUCUNE connaissance d'un fournisseur précis ici : format
 * "Chat Completions", devenu la norme de facto la plus largement supportée
 * (OpenAI, Azure OpenAI, Groq, Together, Mistral, DeepSeek, OpenRouter — qui
 * relaie lui-même vers à peu près n'importe quel modèle, Anthropic/Gemini
 * inclus, sous ce même format —, et tout serveur auto-hébergé compatible
 * comme vLLM/LM Studio). L'administrateur pointe où il veut via .env, sans
 * que le code ait besoin de savoir lequel.
 *
 * Entièrement optionnelle : apiUrl vide par défaut, aucune de ces variables
 * n'est requise. Voir aussi OllamaProperties (Ollama local, optionnel lui
 * aussi).
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class ExternalLlmProperties
{
    private String apiUrl;
    private String apiKey;
    private String model;

    public boolean estConfiguree()
    {
        return StringUtils.hasText(apiUrl);
    }
}
