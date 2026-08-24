package made.archive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig 
{

    @Bean
    public CorsFilter corsFilter() 
    {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        
        // Autorise les origines spécifiques
        config.addAllowedOrigin("http://localhost:5173");
        config.addAllowedOrigin("http://localhost:3000");

        // Autorise tous les en-têtes
        config.addAllowedHeader("*");

        // Autorise toutes les méthodes HTTP : GET, POST, etc.
        config.addAllowedMethod("*");

        // Autorise les cookies et headers d'authentification
        config.setAllowCredentials(true);

        // Applique cette config à toutes les routes
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
