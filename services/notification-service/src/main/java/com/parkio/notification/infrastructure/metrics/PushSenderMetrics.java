package com.parkio.notification.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Provider-level push counters (no PII, no device tokens). */
@Component
public class PushSenderMetrics {

    private final Counter sent;
    private final Counter failed;
    private final Counter invalidToken;
    private final Counter providerUnavailable;
    private final Counter outcomeUnknown;

    public PushSenderMetrics(MeterRegistry registry) {
        this.sent = Counter.builder("parkio.notification.push.sent.count")
                .description("Push notifications accepted by the provider")
                .register(registry);
        this.failed = Counter.builder("parkio.notification.push.failed.count")
                .description("Push notifications rejected by the provider, credential rejections included")
                .register(registry);
        this.invalidToken = Counter.builder("parkio.notification.push.invalid_token.count")
                .description("Push attempts rejected because the device token is no longer valid")
                .register(registry);
        this.providerUnavailable = Counter.builder("parkio.notification.push.provider_unavailable.count")
                .description("Push sends that failed retryably: no connection to the provider, or an HTTP"
                        + " rejection other than 401/403")
                .register(registry);
        this.outcomeUnknown = Counter.builder("parkio.notification.push.outcome_unknown.count")
                .description("Push sends whose acceptance is unknown (read timeout, reset or unreadable response"
                        + " after connecting); terminal, never resent")
                .register(registry);
    }

    public void sent() {
        sent.increment();
    }

    public void failed() {
        failed.increment();
    }

    public void invalidToken() {
        invalidToken.increment();
    }

    public void providerUnavailable() {
        providerUnavailable.increment();
    }

    public void outcomeUnknown() {
        outcomeUnknown.increment();
    }
}