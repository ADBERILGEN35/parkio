package com.parkio.auth.presentation;

import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.command.LoginCommand;
import com.parkio.auth.application.command.LogoutCommand;
import com.parkio.auth.application.command.RefreshTokenCommand;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.result.AuthResult;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.presentation.dto.AuthResponse;
import com.parkio.auth.presentation.dto.LoginRequest;
import com.parkio.auth.presentation.dto.LogoutRequest;
import com.parkio.auth.presentation.dto.RefreshTokenRequest;
import com.parkio.auth.presentation.dto.RegisterRequest;
import com.parkio.auth.presentation.dto.UserResponse;
import com.parkio.auth.shared.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication API. Translates HTTP requests into application commands and
 * application results into response DTOs — JPA entities and domain objects
 * never cross this boundary.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthApplicationService authService;

    public AuthController(AuthApplicationService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResult result = authService.register(
                new RegisterCommand(request.email(), request.password()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse.from(result));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(new LoginCommand(request.email(), request.password()));
        return AuthResponse.from(result);
    }

    @PostMapping("/refresh-token")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResult result = authService.refresh(new RefreshTokenCommand(request.refreshToken()));
        return AuthResponse.from(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(new LogoutCommand(request.refreshToken()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
        AuthUser user = authService.currentUser(principal.userId());
        return UserResponse.from(user);
    }
}
