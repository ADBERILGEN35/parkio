package com.parkio.parking.outcome.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.OutcomeSnapshot;
import com.parkio.parking.outcome.evaluation.OutcomeEvaluationContext;
import com.parkio.parking.outcome.engine.OutcomeValidationEngine;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;
import com.parkio.parking.outcome.normalization.OutcomeEvidenceFactory;
import com.parkio.parking.outcome.normalization.ParkingSpotOutcomeContext;
import com.parkio.parking.outcome.policy.OutcomePolicyConfig;
import com.parkio.parking.outcome.policy.OutcomePolicyVersion;
import com.parkio.parking.outcome.signal.OutcomeSignal;
import com.parkio.parking.outcome.signal.OutcomeSignalSource;
import com.parkio.parking.outcome.signal.OutcomeSignalType;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutcomeReplayerTest {

    private static final UUID SPOT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant BASE = Instant.parse("2026-07-28T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(10);

    @Test
    void snapshotReplayMatchesOriginalEvaluation() {
        OutcomeSnapshot snapshot = captureSnapshot(BASE.plus(Duration.ofMinutes(4)));
        OutcomeReplayComparison comparison = OutcomeReplayer.replayAndCompare(snapshot);
        assertThat(comparison.matches()).isTrue();
        assertThat(comparison.replayed().classification()).isEqualTo(OutcomeClassification.CONFIRMED_CORRECT);
    }

    @Test
    void unknownPolicyVersionFailsExplicitly() {
        assertThatThrownBy(() -> OutcomeReplayer.forPolicyVersion(OutcomePolicyVersion.of("outcome-validation-v2")))
                .isInstanceOf(UnsupportedOutcomePolicyVersionException.class)
                .hasMessageContaining("outcome-validation-v2");
    }

    private OutcomeSnapshot captureSnapshot(Instant evaluatedAt) {
        ParkingSpotOutcomeContext context = new ParkingSpotOutcomeContext(
                SPOT_ID, ParkingSpotStatus.VERIFIED, BASE, BASE, BASE.plus(WINDOW), evaluatedAt, 1, 0, 1.0);
        OutcomeTimeline timeline = OutcomeTimeline.of(BASE, BASE.plus(WINDOW), List.of(
                new OutcomeSignal(OutcomeSignalType.PUBLISHED, OutcomeSignalSource.SYSTEM, BASE),
                new OutcomeSignal(OutcomeSignalType.COMMUNITY_CLAIM, OutcomeSignalSource.COMMUNITY, BASE.plus(Duration.ofMinutes(3)))));
        OutcomeEvidence evidence = OutcomeEvidenceFactory.fromContext(context, timeline);
        OutcomeEvaluationContext evalContext = new OutcomeEvaluationContext(
                evaluatedAt, OutcomePolicyConfig.POLICY_VERSION, WINDOW);
        OutcomeEvaluation evaluation = new OutcomeValidationEngine().evaluate(evidence, evalContext);
        return new OutcomeSnapshot(evidence, evalContext, evaluation);
    }
}