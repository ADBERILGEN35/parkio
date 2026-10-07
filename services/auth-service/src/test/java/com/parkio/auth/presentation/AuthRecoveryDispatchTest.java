package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.command.ForgotPasswordCommand;
import com.parkio.auth.application.command.ResendVerificationCommand;
import com.parkio.auth.infrastructure.metrics.AuthMetrics;
import com.parkio.auth.infrastructure.notification.EmailDeliveryException;
import com.parkio.auth.infrastructure.web.ClientIdentityResolver;
import com.parkio.auth.presentation.dto.ForgotPasswordRequest;
import com.parkio.auth.presentation.dto.ResendVerificationRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * CL-F14.2: the public recovery endpoints answer before their work runs, so the response time
 * does not depend on whether the account exists, and a delivery failure in that work stays out
 * of the response.
 */
class AuthRecoveryDispatchTest {

    private final AuthApplicationService authService = mock(AuthApplicationService.class);
    private final List<Runnable> dispatched = new ArrayList<>();
    private final AuthController controller = new AuthController(
            authService, new AuthMetrics(new SimpleMeterRegistry()), refreshCookieProperties(), dispatched::add,
            new ClientIdentityResolver());

    @Test
    void forgotPasswordAnswersBeforeTheWorkRuns() {
        ResponseEntity<Void> response = controller.forgotPassword(
                new ForgotPasswordRequest("user@parkio.app", "en"), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(authService);
        assertThat(dispatched).hasSize(1);

        dispatched.get(0).run();

        verify(authService).forgotPassword(any(ForgotPasswordCommand.class));
    }

    @Test
    void resendVerificationAnswersBeforeTheWorkRuns() {
        ResponseEntity<Void> response = controller.resendVerification(
                new ResendVerificationRequest("user@parkio.app", "tr"), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verifyNoInteractions(authService);
        assertThat(dispatched).hasSize(1);

        dispatched.get(0).run();

        verify(authService).resendVerification(any(ResendVerificationCommand.class));
    }

    @Test
    void aFailureInTheDispatchedWorkIsAbsorbed() {
        doThrow(new EmailDeliveryException("provider 503 (provider_5xx)", null))
                .when(authService).forgotPassword(any(ForgotPasswordCommand.class));
        doThrow(new IllegalStateException("database unavailable"))
                .when(authService).resendVerification(any(ResendVerificationCommand.class));
        controller.forgotPassword(new ForgotPasswordRequest("user@parkio.app", "en"), new MockHttpServletRequest());
        controller.resendVerification(new ResendVerificationRequest("user@parkio.app", "en"), new MockHttpServletRequest());

        assertThatCode(() -> dispatched.forEach(Runnable::run)).doesNotThrowAnyException();
    }

    private static RefreshCookieProperties refreshCookieProperties() {
        RefreshCookieProperties properties = new RefreshCookieProperties();
        properties.setAllowedOrigins(List.of("http://localhost:5173"));
        return properties;
    }
}
