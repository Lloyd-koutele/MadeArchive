package made.archive.controller;

import lombok.RequiredArgsConstructor;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import made.archive.service.auth.AuthService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController 
{

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<AuthService.AuthResponse> login(@RequestBody AuthService.LoginRequest request) 
    {

        AuthService.AuthResponse response = authService.authenticate(request);

        if (!response.isSuccess()) 
        {
            return ResponseEntity.status(401).body(response);
        }

        return ResponseEntity.ok(response);
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
