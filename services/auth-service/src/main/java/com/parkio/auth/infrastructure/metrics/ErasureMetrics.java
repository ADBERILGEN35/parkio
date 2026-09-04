package com.parkio.auth.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class ErasureMetrics {

    private final Counter requested;
    private final Counter completed;
    private final Counter failed;
    private final Timer duration;
    private final AtomicLong stuck = new AtomicLong(0);

    public ErasureMetrics(MeterRegistry registry) {
        this.requested = Counter.builder("parkio.erasure.requested")
                .description("Account erasure requests accepted")
                .register(registry);
        this.completed = Counter.builder("parkio.erasure.completed")
                .description("Account erasures marked complete")
                .register(registry);
        this.failed = Counter.builder("parkio.erasure.failed")
                .description("Account erasures marked retryable-failed")
                .register(registry);
        this.duration = Timer.builder("parkio.erasure.duration")
                .description("Time from erasure request to COMPLETE")
                .register(registry);
        Gauge.builder("parkio.erasure.stuck", stuck, AtomicLong::get)
                .description("Erasure requests still incomplete beyond operational SLA")
                .register(registry);
    }

    public void requested() {
        requested.increment();
    }

    public void completed(Duration elapsed) {
        completed.increment();
        if (elapsed != null && !elapsed.isNegative()) {
            duration.record(elapsed);
        }
    }

    public void failed() {
        failed.increment();
    }

    public void setStuck(long value) {
        stuck.set(value);
    }

    public Duration sla() {
        return Duration.ofHours(1);
    }
}
