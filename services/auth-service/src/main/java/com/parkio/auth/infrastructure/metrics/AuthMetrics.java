package com.parkio.auth.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Login outcome counters exported at {@code /actuator/prometheus}:
 * {@code parkio.auth.login.{success,failure}.count}.
 *
 * <p>Counters only — no email, user id, or other PII is ever tagged. A spike in
 * failures signals credential stuffing or an auth regression.
 */
@Component
public class AuthMetrics {

    private final Counter loginSuccess;
    private final Counter loginFailure;

    public AuthMetrics(MeterRegistry registry) {
        this.loginSuccess = Counter.builder("parkio.auth.login.success.count")
                .description("Successful logins")
                .register(registry);
        this.loginFailure = Counter.builder("parkio.auth.login.failure.count")
                .description("Failed logins (bad credentials, suspended account, ...)")
                .register(registry);
    }

    public void loginSucceeded() {
        loginSuccess.increment();
    }

    public void loginFailed() {
        loginFailure.increment();
    }
}
