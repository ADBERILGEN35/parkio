package com.parkio.parking.availability.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.availability.AvailabilityEvaluation;
import com.parkio.parking.availability.AvailabilitySnapshot;
import com.parkio.parking.availability.AvailabilityState;
import com.parkio.parking.availability.evaluation.AvailabilityEvaluationContext;
import com.parkio.parking.availability.engine.AvailabilityEngine;
import com.parkio.parking.availability.evidence.AvailabilityEvidence;
import com.parkio.parking.availability.policy.AvailabilityPolicyConfig;
import com.parkio.parking.availability.policy.AvailabilityPolicyVersion;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AvailabilityReplayerTest {

    private static final UUID SPOT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant BASE = Instant.parse("2026-07-28T12:00:00Z");
    private static final Duration ACTIVE = Duration.ofMinutes(10);

    private final AvailabilityEngine engine = new AvailabilityEngine();

    @Test
    void snapshotReplayMatchesOriginalEvaluation() {
        AvailabilitySnapshot snapshot = captureSnapshot(BASE.plus(Duration.ofMinutes(1)));
        AvailabilityReplayComparison comparison = AvailabilityReplayer.replayAndCompare(snapshot);
        assertThat(comparison.matches()).isTrue();
        assertThat(comparison.replayed().state()).isEqualTo(AvailabilityState.AVAILABLE);
    }

    @Test
    void unknownPolicyVersionFailsExplicitly() {
        assertThatThrownBy(() -> AvailabilityReplayer.forPolicyVersion(AvailabilityPolicyVersion.of("availability-v2")))
                .isInstanceOf(UnsupportedAvailabilityPolicyVersionException.class)
                .hasMessageContaining("availability-v2");
    }

    private AvailabilitySnapshot captureSnapshot(Instant evaluatedAt) {
        AvailabilityEvidence evidence = new AvailabilityEvidence(
                SPOT_ID,
                ParkingSpotStatus.ACTIVE,
                LegalStatus.LEGAL,
                BASE,
                BASE,
                BASE.plus(ACTIVE),
                0,
                0,
                1.0);
        AvailabilityEvaluationContext context = new AvailabilityEvaluationContext(
                evaluatedAt,
                AvailabilityPolicyConfig.POLICY_VERSION,
                ACTIVE);
        AvailabilityEvaluation evaluation = engine.evaluate(evidence, context);
        return new AvailabilitySnapshot(evidence, context, evaluation);
    }
}