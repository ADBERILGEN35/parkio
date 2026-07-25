package com.parkio.parking.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * Micrometer meters for ACTIVE parking-session lifecycle (confirm, remind, auto-complete).
 * Label cardinality is bounded (stage=FIRST|SECOND only). Never label by userId/sessionId.
 */
@Component
public class ParkingSessionLifecycleMetrics {

    private final MeterRegistry registry;
    private final Counter autoCompleted;
    private final Counter confirmations;
    private final Counter schedulerProcessed;
    private final Counter schedulerFailed;
    private final Counter retentionDeleted;
    private final Timer schedulerDuration;
    private final AtomicLong activeSessions = new AtomicLong(0);

    public ParkingSessionLifecycleMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.autoCompleted = Counter.builder("parking.sessions.auto_completed")
                .description("ACTIVE parking sessions auto-completed by the stale-session scheduler")
                .register(registry);
        this.confirmations = Counter.builder("parking.sessions.confirmation")
                .description("ACTIVE parking sessions confirmed still parked by the owner")
                .register(registry);
        this.schedulerProcessed = Counter.builder("parking.sessions.scheduler.processed")
                .description("Candidate rows examined by the stale-session scheduler")
                .register(registry);
        this.schedulerFailed = Counter.builder("parking.sessions.scheduler.failed")
                .description("Stale-session scheduler tick failures")
                .register(registry);
        this.retentionDeleted = Counter.builder("parking.sessions.retention.deleted")
                .description("Terminal parking sessions deleted by retention (when enabled)")
                .register(registry);
        this.schedulerDuration = Timer.builder("parking.sessions.scheduler.duration")
                .description("Wall time of a full stale-session scheduler tick")
                .register(registry);
        Gauge.builder("parking.sessions.active", activeSessions, AtomicLong::doubleValue)
                .description("Approximate number of ACTIVE parking sessions")
                .register(registry);
    }

    public void recordAutoCompleted(int count) {
        if (count > 0) {
            autoCompleted.increment(count);
        }
    }

    public void recordConfirmation() {
        confirmations.increment();
    }

    public void recordReminderSent(String stage, int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("parking.sessions.reminder_sent")
                .description("Stale-session reminder events published to the outbox")
                .tag("stage", stage == null ? "UNKNOWN" : stage)
                .register(registry)
                .increment(count);
    }

    public void recordRetentionDeleted(int count) {
        if (count > 0) {
            retentionDeleted.increment(count);
        }
    }

    public void recordSchedulerProcessed(int count) {
        if (count > 0) {
            schedulerProcessed.increment(count);
        }
    }

    public void recordSchedulerFailed() {
        schedulerFailed.increment();
    }

    public Timer.Sample startSchedulerTimer() {
        return Timer.start();
    }

    public void stopSchedulerTimer(Timer.Sample sample) {
        sample.stop(schedulerDuration);
    }

    public void refreshActiveCount(LongSupplier countSupplier) {
        activeSessions.set(Math.max(0L, countSupplier.getAsLong()));
    }
}