package com.parkio.parking.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.outcome.confidence.OutcomeConfidence;
import com.parkio.parking.outcome.policy.OutcomePolicyConfig;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutcomeValidationMetricsTest {

    @Test
    void recordsBoundedTagsOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutcomeValidationMetrics metrics = new OutcomeValidationMetrics(registry);
        UUID spotId = UUID.randomUUID();
        Instant at = Instant.parse("2026-07-28T10:00:00Z");
        OutcomeEvaluation evaluation = new OutcomeEvaluation(
                spotId,
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeConfidence.of(90),
                OutcomeReason.COMMUNITY_CLAIM_CONFIRMED,
                Set.of(OutcomeReason.COMMUNITY_CLAIM_CONFIRMED),
                OutcomeTimeline.of(at, at.plus(Duration.ofMinutes(10)), java.util.List.of()),
                Duration.ofMinutes(3),
                false,
                OutcomePolicyConfig.POLICY_VERSION,
                at);
        metrics.recordEvaluation(evaluation, Duration.ofMillis(4));
        assertThat(registry.find("parkio.parking.outcome.classification")
                .tag("classification", "CONFIRMED_CORRECT")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.find("parkio.parking.outcome.processing.duration").timer().count()).isEqualTo(1L);
        assertThat(registry.find("parkio.parking.outcome.classification")
                .tag("classification", "CONFIRMED_CORRECT")
                .counter()
                .count()).isEqualTo(1.0);
    }
}