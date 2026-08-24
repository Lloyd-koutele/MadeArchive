package made.archive.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.logging.Logger;


@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter
{

    private static final Logger logger = Logger.getLogger(JwtAuthFilter.class.getName());

    private final JwtService jwtService;

    /**
     * Résolution utilisateur MISE EN CACHE (Redis) — voir AuthCacheService.
     * Séparée du bean UserDetailsService de SecurityConfig (utilisé par
     * /api/login, resté non caché, seul à porter le vrai mot de passe).
     */
    private final AuthCacheService authCacheService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException
    {
        // Vérifier d'abord l'en-tête Authorization
        String jwt = null;
        final String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith(JwtService.PREFIX)) 
        {
            jwt = authHeader.substring(JwtService.PREFIX.length()).trim();
        } 
        else 
        {
            // Vérifier si le token est fourni en paramètre de requête
            String tokenParam = request.getParameter("token");
            if (tokenParam != null && !tokenParam.isEmpty()) 
            {
                // Vérifier si le token contient déjà le préfixe Bearer
                if (tokenParam.startsWith(JwtService.PREFIX)) 
                {
                    jwt = tokenParam.substring(JwtService.PREFIX.length()).trim();
                } 
                else 
                {
                    jwt = tokenParam.trim();
                }
                logger.info("Token JWT trouvé dans les paramètres de requête");
            }
        }
        
        // Si aucun token n'est trouvé, continuer la chaîne de filtres
        if (jwt == null) 
        {
            filterChain.doFilter(request, response);
            return;
        }

        if (jwt.isEmpty())
        {
            logger.warning("Token JWT est vide");
            filterChain.doFilter(request, response);
            return;
        }

        try
        {
            final String userEmail = jwtService.extractUsername(jwt);

            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null)
            {
                CachedUserAuth cached = authCacheService.resolveUserAuth(userEmail);
                if (cached == null)
                {
                    throw new UsernameNotFoundException("Utilisateur non trouvé : " + userEmail);
                }
                UserDetails userDetails = authCacheService.toUserDetails(cached);

                if (jwtService.isTokenValid(jwt, userEmail))
                {
                    String failureReason = resolveFailureReason(userDetails, jwt);

                    if (failureReason == null)
                    {
                        var authToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                        logger.info("Authentification réussie pour : " + userEmail);
                    }
                    else
                    {
                        // Compte bloqué, rôle changé ou mot de passe changé depuis l'émission
                        // de ce token : on ne l'authentifie pas, et on transmet la raison à
                        // l'authenticationEntryPoint (voir SecurityConfig) afin que le client
                        // puisse distinguer ce cas d'une simple absence/expiration de token.
                        request.setAttribute("authFailureReason", failureReason);
                        logger.warning("Session invalidée (" + failureReason + ") pour : " + userEmail);
                    }
                }
                else
                {
                    logger.warning("Token JWT invalide pour : " + userEmail);
                }
            }
        }
        catch (Exception e)
        {
            logger.severe("Erreur JWT : " + e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Retourne une raison de rejet si ce token, bien que structurellement valide et non
     * expiré, ne doit plus être accepté : compte désactivé, ou session invalidée après
     * blocage / changement de rôle / changement de mot de passe (voir UserService).
     * Retourne null si le token reste utilisable.
     */
    private String resolveFailureReason(UserDetails userDetails, String jwt)
    {
        if (!userDetails.isEnabled())
        {
            return "ACCOUNT_BLOCKED";
        }

        if (userDetails instanceof UserDetailsImpl impl)
        {
            Instant invalidatedAt = impl.getUser().getSessionInvalidatedAt();
            if (invalidatedAt != null)
            {
                // On tronque à la seconde : le claim "iat" du JWT n'a qu'une précision
                // à la seconde, contrairement à Instant.now(). Sans ça, un nouveau
                // token émis dans la même seconde que l'invalidation pourrait être
                // rejeté à tort.
                Instant invalidatedAtFloor = invalidatedAt.truncatedTo(ChronoUnit.SECONDS);
                Instant issuedAt = jwtService.extractIssuedAt(jwt).toInstant();
                if (issuedAt.isBefore(invalidatedAtFloor))
                {
                    return "SESSION_INVALIDATED";
                }
            }
        }

        return null;
    }
}
