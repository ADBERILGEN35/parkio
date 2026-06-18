package com.parkio.auth.infrastructure.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** PII-free counters for transactional email delivery. */
@Component
public class EmailDeliveryMetrics {

    private final Counter emailSent;
    private final Counter emailFailed;
    private final Counter verificationSent;

    public EmailDeliveryMetrics(MeterRegistry registry) {
        this.emailSent = Counter.builder("email_sent")
                .description("Transactional emails accepted by the provider")
                .register(registry);
        this.emailFailed = Counter.builder("email_failed")
                .description("Transactional emails rejected by or not delivered to the provider")
                .register(registry);
        this.verificationSent = Counter.builder("email_verification_sent")
                .description("Email verification messages accepted by the provider")
                .register(registry);
    }

    public void emailSent() {
        emailSent.increment();
    }

    public void emailFailed() {
        emailFailed.increment();
    }

    public void verificationSent() {
        verificationSent.increment();
    }
}
