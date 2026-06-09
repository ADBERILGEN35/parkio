package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.command.LoginCommand;
import com.parkio.auth.application.result.AuthResult;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.metrics.AuthMetrics;
import com.parkio.auth.presentation.dto.LoginRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code parkio.auth.login.{success,failure}.count} increment on the login
 * path — counters only, no PII tags.
 */
class AuthLoginMetricsTest {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");

    private final AuthApplicationService authService = mock(AuthApplicationService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AuthController controller = new AuthController(authService, new AuthMetrics(registry));

    @Test
    void successfulLoginIncrementsSuccessCounter() {
        AuthUser user = AuthUser.register(
                "user@parkio.app", "hash", Set.of(new Role(UUID.randomUUID(), RoleName.USER)), NOW);
        when(authService.login(any(LoginCommand.class))).thenReturn(
                new AuthResult(user, "access", NOW.plusSeconds(900), "refresh", NOW.plusSeconds(86400)));

        controller.login(new LoginRequest("user@parkio.app", "secret-password"));

        assertThat(registry.counter("parkio.auth.login.success.count").count()).isEqualTo(1.0);
        assertThat(registry.counter("parkio.auth.login.failure.count").count()).isZero();
    }

    @Test
    void failedLoginIncrementsFailureCounterAndRethrows() {
        when(authService.login(any(LoginCommand.class)))
                .thenThrow(new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

        assertThatThrownBy(() -> controller.login(new LoginRequest("user@parkio.app", "wrong")))
                .isInstanceOf(AuthException.class);

        assertThat(registry.counter("parkio.auth.login.failure.count").count()).isEqualTo(1.0);
        assertThat(registry.counter("parkio.auth.login.success.count").count()).isZero();
    }
}
