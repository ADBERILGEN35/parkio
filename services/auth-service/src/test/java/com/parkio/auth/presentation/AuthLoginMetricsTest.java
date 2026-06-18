package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.command.ForgotPasswordCommand;
import com.parkio.auth.application.command.LoginCommand;
import com.parkio.auth.application.result.AuthResult;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.domain.exception.LoginLockedException;
import com.parkio.auth.infrastructure.metrics.AuthMetrics;
import com.parkio.auth.presentation.dto.ForgotPasswordRequest;
import com.parkio.auth.presentation.dto.LoginRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Verifies login outcome counters increment on the login path — counters only,
 * no PII tags.
 */
class AuthLoginMetricsTest {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");

    private final AuthApplicationService authService = mock(AuthApplicationService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AuthController controller = new AuthController(
            authService, new AuthMetrics(registry), refreshCookieProperties());

    @Test
    void successfulLoginIncrementsSuccessCounter() {
        AuthUser user = new AuthUser(
                UUID.randomUUID(),
                "user@parkio.app",
                "hash",
                com.parkio.auth.domain.AuthUserStatus.ACTIVE,
                null,
                Set.of(new Role(UUID.randomUUID(), RoleName.USER)),
                NOW,
                null);
        when(authService.login(any(LoginCommand.class))).thenReturn(
                new AuthResult(user, "access", NOW.plusSeconds(900), "refresh", NOW.plusSeconds(86400)));

        controller.login(new LoginRequest("user@parkio.app", "secret-password"), new MockHttpServletRequest());

        assertThat(registry.counter("login_success").count()).isEqualTo(1.0);
        assertThat(registry.counter("login_failures").count()).isZero();
        assertThat(registry.counter("login_lockouts").count()).isZero();
    }

    @Test
    void failedLoginIncrementsFailureCounterAndRethrows() {
        when(authService.login(any(LoginCommand.class)))
                .thenThrow(new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

        assertThatThrownBy(() -> controller.login(
                        new LoginRequest("user@parkio.app", "wrong"), new MockHttpServletRequest()))
                .isInstanceOf(AuthException.class);

        assertThat(registry.counter("login_failures").count()).isEqualTo(1.0);
        assertThat(registry.counter("login_success").count()).isZero();
        assertThat(registry.counter("login_lockouts").count()).isZero();
    }

    @Test
    void lockedLoginIncrementsFailureAndLockoutCounters() {
        when(authService.login(any(LoginCommand.class))).thenThrow(new LoginLockedException());

        assertThatThrownBy(() -> controller.login(
                        new LoginRequest("user@parkio.app", "wrong"), new MockHttpServletRequest()))
                .isInstanceOf(AuthException.class)
                .hasMessage(AuthErrorCode.INVALID_CREDENTIALS.defaultMessage());

        assertThat(registry.counter("login_failures").count()).isEqualTo(1.0);
        assertThat(registry.counter("login_lockouts").count()).isEqualTo(1.0);
        assertThat(registry.counter("login_success").count()).isZero();
    }

    @Test
    void forgotPasswordReturnsOkAndDelegatesWithoutLeakingAccountState() {
        var response = controller.forgotPassword(
                new ForgotPasswordRequest("User@Example.com"), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(authService).forgotPassword(new ForgotPasswordCommand("User@Example.com"));
    }

    private static RefreshCookieProperties refreshCookieProperties() {
        RefreshCookieProperties properties = new RefreshCookieProperties();
        properties.setAllowedOrigins(java.util.List.of("http://localhost:5173"));
        return properties;
    }
}
