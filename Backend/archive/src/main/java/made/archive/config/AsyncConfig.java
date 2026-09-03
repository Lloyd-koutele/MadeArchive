package made.archive.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Exécuteur partagé pour les traitements @Async :
 *   - RegexGenerationService — génération des regex d'extraction via Ollama,
 *     potentiellement lente (~1 minute), mais rare (une seule fois par type
 *     de document, ou après une réinitialisation manuelle/automatique) ;
 *   - HorodatageService.horodaterApresUpload — appel TSA (timeout 5s, voir
 *     sa Javadoc), déclenché lui à CHAQUE document archivé, donc bien plus
 *     fréquent. Pool élargi par rapport à l'origine (2/4 → 4/8) pour que ce
 *     chemin, désormais chaud, ne fasse jamais la queue derrière un appel
 *     Ollama lent occupant les deux seuls threads d'avant.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer
{
    @Override
    public Executor getAsyncExecutor()
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("async-");
        executor.initialize();
        return executor;
    }
}
