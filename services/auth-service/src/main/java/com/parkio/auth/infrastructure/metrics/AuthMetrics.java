package com.parkio.auth.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Login outcome counters exported at {@code /actuator/prometheus}.
 *
 * <p>Counters only — no email, user id, or other PII is ever tagged. A spike in
 * failures signals credential stuffing or an auth regression.
 */
@Component
public class AuthMetrics {

    private final Counter loginSuccess;
    private final Counter loginFailure;
    private final Counter loginLockout;

    public AuthMetrics(MeterRegistry registry) {
        this.loginSuccess = Counter.builder("login_success")
                .description("Successful logins")
                .register(registry);
        this.loginFailure = Counter.builder("login_failures")
                .description("Failed logins (bad credentials, suspended account, ...)")
                .register(registry);
        this.loginLockout = Counter.builder("login_lockouts")
                .description("Login attempts blocked by account lockout")
                .register(registry);
    }

    public void loginSucceeded() {
        loginSuccess.increment();
    }

    public void loginFailed() {
        loginFailure.increment();
    }

    public void loginLockedOut() {
        loginLockout.increment();
    }
}
