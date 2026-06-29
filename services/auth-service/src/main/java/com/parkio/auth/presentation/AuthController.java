package com.parkio.auth.presentation;

import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.command.ChangePasswordCommand;
import com.parkio.auth.application.command.ForgotPasswordCommand;
import com.parkio.auth.application.command.LoginCommand;
import com.parkio.auth.application.command.LogoutCommand;
import com.parkio.auth.application.command.RefreshTokenCommand;
import com.parkio.auth.application.command.ResendVerificationCommand;
import com.parkio.auth.application.command.ResetPasswordCommand;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.command.VerifyEmailCommand;
import com.parkio.auth.application.result.AuthResult;
import com.parkio.auth.application.result.RegisterResult;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.exception.LoginLockedException;
import com.parkio.auth.infrastructure.metrics.AuthMetrics;
import com.parkio.auth.presentation.dto.AuthResponse;
import com.parkio.auth.presentation.dto.ChangePasswordRequest;
import com.parkio.auth.presentation.dto.ForgotPasswordRequest;
import com.parkio.auth.presentation.dto.LoginRequest;
import com.parkio.auth.presentation.dto.MobileTokenRequest;
import com.parkio.auth.presentation.dto.RegisterRequest;
import com.parkio.auth.presentation.dto.ResendVerificationRequest;
import com.parkio.auth.presentation.dto.ResetPasswordRequest;
import com.parkio.auth.presentation.dto.UserResponse;
import com.parkio.auth.presentation.dto.VerifyEmailRequest;
import com.parkio.auth.presentation.openapi.StandardApiResponses;
import com.parkio.auth.shared.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseCookie;
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
@Tag(name = "Authentication", description = "Register, login, refresh and session endpoints")
@StandardApiResponses
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthApplicationService authService;
    private final AuthMetrics authMetrics;
    private final RefreshCookieProperties refreshCookie;

    public AuthController(AuthApplicationService authService,
                          AuthMetrics authMetrics,
                          RefreshCookieProperties refreshCookie) {
        this.authService = authService;
        this.authMetrics = authMetrics;
        this.refreshCookie = refreshCookie;
    }

    @Operation(summary = "Register a new account")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        validateOriginIfPresent(httpRequest);
        RegisterResult result = authService.register(
                new RegisterCommand(request.email(), request.password()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse.pendingVerification(result));
    }

    @Operation(summary = "Login with email and password")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        validateOriginIfPresent(httpRequest);
        AuthResult result;
        try {
            result = authService.login(new LoginCommand(request.email(), request.password()));
        } catch (RuntimeException ex) {
            authMetrics.loginFailed();
            if (ex instanceof LoginLockedException) {
                authMetrics.loginLockedOut();
            }
            throw ex;
        }
        authMetrics.loginSucceeded();
        if (isMobileClient(httpRequest)) {
            // Native client: refresh token returned in the body for SecureStore; no cookie.
            return ResponseEntity.ok(AuthResponse.fromMobile(result));
        }
        return withRefreshCookie(ResponseEntity.ok(), result).body(AuthResponse.from(result));
    }

    @Operation(summary = "Rotate refresh token")
    @PostMapping("/refresh-token")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request,
                                                @RequestBody(required = false) MobileTokenRequest body) {
        boolean mobile = isMobileClient(request);
        // Origin enforcement is a CSRF guard for the browser cookie transport. Native
        // clients carry no ambient cookie and no Origin header, and present the refresh
        // token explicitly in the body, so the guard does not apply to them.
        if (!mobile) {
            validateOrigin(request);
        }
        String token = mobile ? mobileRefreshToken(body) : refreshTokenCookie(request);
        AuthResult result = authService.refresh(new RefreshTokenCommand(token));
        if (mobile) {
            return ResponseEntity.ok(AuthResponse.fromMobile(result));
        }
        return withRefreshCookie(ResponseEntity.ok(), result).body(AuthResponse.from(result));
    }

    @Operation(summary = "Verify a registered email address")
    @PostMapping("/verify-email")
    public UserResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request,
                                    HttpServletRequest httpRequest) {
        validateOriginIfPresent(httpRequest);
        AuthUser user = authService.verifyEmail(new VerifyEmailCommand(request.token()));
        return UserResponse.from(user);
    }

    @Operation(summary = "Resend email verification link")
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request,
                                                   HttpServletRequest httpRequest) {
        validateOriginIfPresent(httpRequest);
        authService.resendVerification(new ResendVerificationCommand(request.email()));
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Request password reset instructions")
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                               HttpServletRequest httpRequest) {
        validateOriginIfPresent(httpRequest);
        authService.forgotPassword(new ForgotPasswordCommand(request.email()));
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Reset password with a reset token")
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                              HttpServletRequest httpRequest) {
        validateOriginIfPresent(httpRequest);
        authService.resetPassword(new ResetPasswordCommand(request.token(), request.newPassword()));
        return ResponseEntity.noContent()
                .header("Set-Cookie", expiredCookie(refreshCookie.getPath()).toString())
                .header("Set-Cookie", expiredCookie(refreshCookie.getLogoutPath()).toString())
                .build();
    }

    @Operation(summary = "Change the current user's password")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthPrincipal principal,
                                               @Valid @RequestBody ChangePasswordRequest request,
                                               HttpServletRequest httpRequest) {
        validateOriginIfPresent(httpRequest);
        authService.changePassword(new ChangePasswordCommand(
                principal.userId(), request.currentPassword(), request.newPassword()));
        return ResponseEntity.noContent()
                .header("Set-Cookie", expiredCookie(refreshCookie.getPath()).toString())
                .header("Set-Cookie", expiredCookie(refreshCookie.getLogoutPath()).toString())
                .build();
    }

    @Operation(summary = "Revoke refresh token (logout)")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request,
                                       @RequestBody(required = false) MobileTokenRequest body) {
        boolean mobile = isMobileClient(request);
        if (!mobile) {
            validateOrigin(request);
        }
        String token = mobile ? mobileRefreshToken(body) : refreshTokenCookie(request);
        authService.logout(new LogoutCommand(token));
        if (mobile) {
            // Nothing to clear on native — there is no cookie.
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.noContent()
                .header("Set-Cookie", expiredCookie(refreshCookie.getPath()).toString())
                .header("Set-Cookie", expiredCookie(refreshCookie.getLogoutPath()).toString())
                .build();
    }

    @Operation(summary = "Revoke all sessions (logout everywhere)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal AuthPrincipal principal,
                                          HttpServletRequest request) {
        boolean mobile = isMobileClient(request);
        if (!mobile) {
            validateOrigin(request);
        }
        authService.logoutAll(principal.userId());
        if (mobile) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.noContent()
                .header("Set-Cookie", expiredCookie(refreshCookie.getPath()).toString())
                .header("Set-Cookie", expiredCookie(refreshCookie.getLogoutPath()).toString())
                .build();
    }

    @Operation(summary = "Current authenticated user")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
        AuthUser user = authService.currentUser(principal.userId());
        return UserResponse.from(user);
    }

    private ResponseEntity.BodyBuilder withRefreshCookie(ResponseEntity.BodyBuilder builder, AuthResult result) {
        return builder.header("Set-Cookie", refreshCookie(result, refreshCookie.getPath()).toString())
                .header("Set-Cookie", refreshCookie(result, refreshCookie.getLogoutPath()).toString());
    }

    private ResponseCookie refreshCookie(AuthResult result, String path) {
        Duration maxAge = Duration.between(java.time.Instant.now(), result.refreshTokenExpiresAt());
        if (maxAge.isNegative()) {
            maxAge = Duration.ZERO;
        }
        return ResponseCookie.from(refreshCookie.getName(), result.refreshToken())
                .httpOnly(true)
                .secure(refreshCookie.isSecure())
                .sameSite(refreshCookie.getSameSite())
                .path(path)
                .maxAge(maxAge)
                .build();
    }

    private ResponseCookie expiredCookie(String path) {
        return ResponseCookie.from(refreshCookie.getName(), "")
                .httpOnly(true)
                .secure(refreshCookie.isSecure())
                .sameSite(refreshCookie.getSameSite())
                .path(path)
                .maxAge(Duration.ZERO)
                .build();
    }

    private String refreshTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            throw invalidRefreshToken();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> refreshCookie.getName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElseThrow(this::invalidRefreshToken);
    }

    /**
     * Whether the request comes from the native mobile app, signalled by the
     * {@code X-Parkio-Client: mobile} header without browser Origin/Referer
     * metadata. This keeps browser contexts on the cookie transport even if a
     * script accidentally sends the mobile header.
     */
    private boolean isMobileClient(HttpServletRequest request) {
        return "mobile".equalsIgnoreCase(request.getHeader("X-Parkio-Client"))
                && request.getHeader("Origin") == null
                && request.getHeader("Referer") == null;
    }

    private String mobileRefreshToken(MobileTokenRequest body) {
        if (body == null || body.refreshToken() == null || body.refreshToken().isBlank()) {
            throw invalidRefreshToken();
        }
        return body.refreshToken();
    }

    private RuntimeException invalidRefreshToken() {
        return new com.parkio.auth.domain.exception.AuthException(
                com.parkio.auth.domain.exception.AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    private void validateOriginIfPresent(HttpServletRequest request) {
        if (request.getHeader("Origin") != null || request.getHeader("Referer") != null) {
            validateOrigin(request);
        }
    }

    private void validateOrigin(HttpServletRequest request) {
        String requestOrigin = request.getHeader("Origin");
        if (requestOrigin == null || requestOrigin.isBlank()) {
            requestOrigin = originFromReferer(request.getHeader("Referer"));
        }
        if (requestOrigin == null || requestOrigin.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatusCode.valueOf(403), "Origin is required for cookie-backed auth requests.");
        }
        String normalized = normalizeOrigin(requestOrigin);
        boolean allowed = refreshCookie.getAllowedOrigins().stream()
                .map(AuthController::normalizeOrigin)
                .anyMatch(normalized::equals);
        if (!allowed) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatusCode.valueOf(403), "Origin is not allowed for cookie-backed auth requests.");
        }
    }

    private static String originFromReferer(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(referer);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            int port = uri.getPort();
            return port == -1 ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String normalizeOrigin(String origin) {
        String trimmed = origin.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
