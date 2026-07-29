package com.parkio.parking.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class DecisionAuditMetricsTest {

    @Test
    void incrementsBoundedWriteAndReplayCountersWithoutIdTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionAuditMetrics metrics = new DecisionAuditMetrics(registry);

        metrics.onWriteSuccess();
        metrics.onWriteFailure();
        metrics.recordReplaySuccess();
        metrics.recordReplayFailure();

        assertThat(registry.find("parkio.parking.decision.audit.write.success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("parkio.parking.decision.audit.write.failure").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("parkio.parking.decision.audit.replay.success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("parkio.parking.decision.audit.replay.failure").counter().count())
                .isEqualTo(1.0);
        registry.getMeters().forEach(meter -> assertThat(meter.getId().getTags())
                .noneMatch(tag -> tag.getKey().toLowerCase().contains("id")));
    }
}