package made.archive.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Base64;
import java.util.function.Function;
import java.util.UUID;
import java.util.stream.Collectors;

import made.archive.entite.User;

@Service
public class JwtService
{
    static final String PREFIX = "Bearer ";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expirationTime;

    private SecretKey getSigningKey()
    {
        byte[] keyBytes = Base64.getDecoder().decode(
                Base64.getEncoder().encodeToString(secret.getBytes())
        );
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(User user) 
    {
    String rolesClaim = user.getRoles().stream()
            .map(role -> role.getName().name()) 
            .collect(Collectors.joining(","));

        return PREFIX + Jwts.builder()
                .id(UUID.randomUUID().toString()) // jti = identifiant unique du token
                .subject(user.getEmail())
                .claim("id", user.getId().toString())
                .claim("role", rolesClaim)
                .claim("nom", user.getNom())
                .claim("prenom", user.getPrenom())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(getSigningKey())
                .compact();
    }

    public long getExpirationTime()
    {
        return expirationTime;
    }

    // Extraire l'email (username) depuis le token
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    // Extraire la date d'émission (iat) — utilisé pour détecter une invalidation forcée de session
    public Date extractIssuedAt(String token) {
        return extractClaim(token, Claims::getIssuedAt);
    }

    // Extraire n'importe quelle info du token
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = parseToken(token);
        return claimsResolver.apply(claims);
    }

    // Vérifier validité du token
    public boolean isTokenValid(String token, String userEmail) {
        final String username = extractUsername(token);
        return username.equals(userEmail) && !isTokenExpired(token);
    }

    // Vérifie si le token est expiré
    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims parseToken(String token) {
        if (token.startsWith(PREFIX)) {
            token = token.replace(PREFIX, "");
        }
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}