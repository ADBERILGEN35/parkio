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

    public PushSenderMetrics(MeterRegistry registry) {
        this.sent = Counter.builder("parkio.notification.push.sent.count")
                .description("Push notifications accepted by the provider")
                .register(registry);
        this.failed = Counter.builder("parkio.notification.push.failed.count")
                .description("Push notifications rejected by the provider")
                .register(registry);
        this.invalidToken = Counter.builder("parkio.notification.push.invalid_token.count")
                .description("Push attempts rejected because the device token is no longer valid")
                .register(registry);
        this.providerUnavailable = Counter.builder("parkio.notification.push.provider_unavailable.count")
                .description("Push provider HTTP/network failures")
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
}