package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.port.DecisionAuditWriteObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Bounded Micrometer metrics for Decision Audit Store writes/replays.
 * Never tags auditId, parkingSpotId, evaluationId, or free-form messages.
 */
@Component
public class DecisionAuditMetrics implements DecisionAuditWriteObserver {

    private final Counter writeSuccess;
    private final Counter writeFailure;
    private final Counter replaySuccess;
    private final Counter replayFailure;

    public DecisionAuditMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        this.writeSuccess = Counter.builder("parkio.parking.decision.audit.write.success")
                .description("Successful decision audit appends")
                .register(registry);
        this.writeFailure = Counter.builder("parkio.parking.decision.audit.write.failure")
                .description("Failed decision audit appends")
                .register(registry);
        this.replaySuccess = Counter.builder("parkio.parking.decision.audit.replay.success")
                .description("Successful offline decision audit replays")
                .register(registry);
        this.replayFailure = Counter.builder("parkio.parking.decision.audit.replay.failure")
                .description("Failed offline decision audit replays")
                .register(registry);
    }

    @Override
    public void onWriteSuccess() {
        writeSuccess.increment();
    }

    @Override
    public void onWriteFailure() {
        writeFailure.increment();
    }

    public void recordReplaySuccess() {
        replaySuccess.increment();
    }

    public void recordReplayFailure() {
        replayFailure.increment();
    }
}