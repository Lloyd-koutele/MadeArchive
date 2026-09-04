package made.archive.security;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;

import made.archive.config.AppProperties;
import made.archive.repository.UserRepository;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
public class SecurityConfig
{

    private final JwtService jwtService;
    private final AppProperties appProperties;

    public SecurityConfig(JwtService jwtService, AppProperties appProperties)
    {
        this.jwtService = jwtService;
        this.appProperties = appProperties;
    }

    /*
     * =========================
     * JWT FILTER
     * =========================
     */
    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtService jwtService, AuthCacheService authCacheService)
    {
        return new JwtAuthFilter(jwtService, authCacheService);
    }

    /*
     * =========================
     * SECURITY FILTER CHAIN
     * =========================
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        // Injection directe de 'jwtAuthFilter' en paramètre pour éviter le piège du userDetailsService(null)

        http
                // API REST + JWT → CSRF off
                .csrf(csrf -> csrf.disable())

                // CORS — seule source de vérité (voir made.archive.config.AppProperties) :
                // une seconde configuration CORS existait en parallèle
                // (made.archive.config.CorsConfig, supprimée), en dur sur
                // localhost:5173 uniquement — c'est CELLE-CI, consultée par la
                // chaîne de filtres Spring Security, qui décide réellement,
                // pas l'autre. Un frontend servi ailleurs (Traefik, domaine
                // réel) se faisait rejeter en 403 malgré la correction de
                // l'autre config — bug découvert en dockerisant le frontend.
                .cors(cors -> cors.configurationSource(request -> {
                    CorsConfiguration config = new CorsConfiguration();
                    java.util.List<String> origines = new java.util.ArrayList<>(Arrays.asList(
                            "http://localhost:5173", "http://localhost:3000"));
                    if (appProperties.getFrontendUrl() != null && !appProperties.getFrontendUrl().isBlank())
                    {
                        origines.add(appProperties.getFrontendUrl());
                    }
                    // Origines supplémentaires (CORS_ADDITIONAL_ORIGINS, ex.
                    // test depuis un téléphone via l'IP du serveur) — voir
                    // AppProperties. Vide par défaut, sans effet.
                    if (appProperties.getCorsAdditionalOrigins() != null
                            && !appProperties.getCorsAdditionalOrigins().isBlank())
                    {
                        Arrays.stream(appProperties.getCorsAdditionalOrigins().split(","))
                                .map(String::trim)
                                .filter(origine -> !origine.isBlank())
                                .forEach(origines::add);
                    }
                    config.setAllowedOrigins(origines);
                    config.setAllowedMethods(Arrays.asList(
                            "GET", "POST", "PUT", "DELETE", "OPTIONS"));
                    config.setAllowedHeaders(Arrays.asList("*"));
                    config.setExposedHeaders(Arrays.asList("Authorization"));
                    config.setAllowCredentials(true);
                    return config;
                }))

                // Stateless
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Gestion des erreurs
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setContentType("application/json");

                            // Posée par JwtAuthFilter quand le token est structurellement valide
                            // mais que le compte a été bloqué / le rôle ou le mot de passe changé
                            // depuis son émission — permet au client d'afficher un message dédié
                            // au lieu d'une simple déconnexion silencieuse.
                            Object reason = req.getAttribute("authFailureReason");
                            if (reason instanceof String reasonStr)
                            {
                                String message = "ACCOUNT_BLOCKED".equals(reasonStr)
                                        ? "Votre compte a été désactivé par un administrateur."
                                        : "Votre session n'est plus valide, veuillez vous reconnecter.";
                                res.getWriter().write(String.format(
                                        "{\"error\":\"Unauthorized\",\"message\":\"%s\",\"reason\":\"%s\"}",
                                        message, reasonStr));
                            }
                            else
                            {
                                res.getWriter().write(
                                        "{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}");
                            }
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(403);
                            res.setContentType("application/json");
                            res.getWriter().write(
                                    "{\"error\":\"Forbidden\",\"message\":\"Access denied\"}");
                        }))

                // Autorisations
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/login").permitAll()
                        .requestMatchers("/api/logout").permitAll()
                        // Préfixe (pas juste "/api/public" exact) : couvre les sous-chemins
                        // réels des contrôleurs publics (ex. /api/public/verify/{id},
                        // /api/public/attestation/{token}/view) — un match exact laissait
                        // ces endpoints, pourtant documentés PUBLIC, retomber sur la règle
                        // anyRequest().authenticated() plus bas.
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin_uo/**").hasRole("ADMIN_UO")
                        // Les projets sont désormais entièrement pilotés par l'éditeur
                        // (création, types attendus, suppression, confidentialité) —
                        // plus besoin de règle dédiée, la règle générale ci-dessous
                        // (EDITOR) suffit ; ADMIN_UO/ADMIN n'y ont qu'un droit de
                        // lecture, déjà couvert par /api/user/** plus bas.
                        .requestMatchers("/api/editor/**").hasRole("EDITOR")
                        .requestMatchers("/api/user/**").hasRole("USER")
                        .anyRequest().authenticated())

                // Filtre JWT (Utilisation propre du bean injecté)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /*
     * =========================
     * USER DETAILS SERVICE
     * =========================
     */
    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository)
    {
        return email -> userRepository.findByEmail(email)
                .map(user -> new UserDetailsImpl(
                        user,
                        user.getRoles().stream()
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName().name()))
                                .collect(Collectors.toList())
                ))
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Utilisateur non trouvé : " + email));
    }

    /*
     * =========================
     * PASSWORD ENCODER
     * =========================
     */
    @Bean
    public PasswordEncoder passwordEncoder() 
    {
        return new BCryptPasswordEncoder();
    }

    /*
     * =========================
     * AUTHENTICATION MANAGER
     * =========================
     */
    @Bean
    public AuthenticationManager authenticationManager( AuthenticationConfiguration config) throws Exception 
    {
        return config.getAuthenticationManager();
    }

    
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy(
                "ROLE_ADMIN > ROLE_USER\n" +
                "ROLE_EDITOR > ROLE_USER\n"+
                "ROLE_ADMIN > ROLE_ADMIN_UO\n" +
                "ROLE_ADMIN_UO > ROLE_USER"
       
        );
    }
}