package made.archive.controller;

import lombok.RequiredArgsConstructor;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import made.archive.exception.BusinessException;
import made.archive.service.auth.AuthService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController
{

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthService.LoginRequest request)
    {
        try
        {
            AuthService.AuthResponse response = authService.authenticate(request);

            if (!response.isSuccess())
            {
                return ResponseEntity.status(401).body(response);
            }

            return ResponseEntity.ok(response);
        }
        // Email ou IP bloqué après trop d'échecs — voir LoginAttemptService.
        // 429 (Too Many Requests) plutôt que 401 : distingue explicitement
        // "identifiants refusés" de "même pas vérifiés, réessayez plus tard".
        catch (BusinessException e)
        {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) 
    {
        return ResponseEntity.ok(authService.refresh(body.get("refreshToken")));
    }
    
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody Map<String, String> body) 
    {
        return ResponseEntity.ok(authService.logout(body.get("refreshToken")));
    }
}
