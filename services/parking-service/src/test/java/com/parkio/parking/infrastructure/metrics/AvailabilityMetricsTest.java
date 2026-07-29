package com.parkio.parking.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.availability.AvailabilityEvaluation;
import com.parkio.parking.availability.AvailabilityFreshness;
import com.parkio.parking.availability.AvailabilityReason;
import com.parkio.parking.availability.AvailabilityState;
import com.parkio.parking.availability.expiration.AvailabilityExpiration;
import com.parkio.parking.availability.policy.AvailabilityPolicyConfig;
import com.parkio.parking.availability.score.AvailabilityScore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AvailabilityMetricsTest {

    @Test
    void recordsBoundedTagsOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AvailabilityMetrics metrics = new AvailabilityMetrics(registry);

        AvailabilityEvaluation evaluation = new AvailabilityEvaluation(
                UUID.randomUUID(),
                AvailabilityState.AVAILABLE,
                AvailabilityScore.of(80),
                AvailabilityFreshness.FRESH,
                AvailabilityReason.TTL_REMAINING_HIGH,
                Set.of(AvailabilityReason.TTL_REMAINING_HIGH),
                AvailabilityExpiration.of(Instant.parse("2026-07-28T10:10:00Z"), Instant.parse("2026-07-28T10:00:00Z")),
                AvailabilityPolicyConfig.POLICY_VERSION,
                Instant.parse("2026-07-28T10:00:00Z"));

        metrics.recordEvaluation(evaluation, Duration.ofMillis(3));

        assertThat(registry.find("parkio.parking.availability.evaluation").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("parkio.parking.availability.state")
                .tag("state", "AVAILABLE")
                .counter()
                .count()).isEqualTo(1.0);
    }
}