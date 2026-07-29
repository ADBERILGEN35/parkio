package com.parkio.parking.outcome.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.outcome.evaluation.OutcomeEvaluationContext;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;
import com.parkio.parking.outcome.normalization.OutcomeEvidenceFactory;
import com.parkio.parking.outcome.normalization.ParkingSpotOutcomeContext;
import com.parkio.parking.outcome.policy.OutcomePolicyConfig;
import com.parkio.parking.outcome.signal.OutcomeSignal;
import com.parkio.parking.outcome.signal.OutcomeSignalSource;
import com.parkio.parking.outcome.signal.OutcomeSignalType;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutcomeValidationEngineTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Instant BASE = Instant.parse("2026-07-28T10:00:00Z");
    private static final UUID SPOT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final OutcomeValidationEngine engine = new OutcomeValidationEngine();

    @Test
    void unpublishedSpotIsUnknown() {
        ParkingSpotOutcomeContext context = new ParkingSpotOutcomeContext(
                SPOT_ID, ParkingSpotStatus.PENDING_VALIDATION, BASE, null, null, BASE, 0, 0, 1.0);
        OutcomeEvidence evidence = OutcomeEvidenceFactory.fromAggregateSnapshot(context);
        OutcomeEvaluation evaluation = evaluate(evidence, BASE.plus(Duration.ofMinutes(1)));
        assertThat(evaluation.classification()).isEqualTo(OutcomeClassification.UNKNOWN);
        assertThat(evaluation.primaryReason()).isEqualTo(OutcomeReason.NOT_YET_PUBLISHED);
    }

    @Test
    void communityClaimIsConfirmedCorrect() {
        OutcomeEvidence evidence = evidenceWithTimeline(List.of(
                signal(OutcomeSignalType.PUBLISHED, BASE),
                signal(OutcomeSignalType.COMMUNITY_CLAIM, BASE.plus(Duration.ofMinutes(3)))));
        OutcomeEvaluation evaluation = evaluate(evidence, BASE.plus(Duration.ofMinutes(4)));
        assertThat(evaluation.classification()).isEqualTo(OutcomeClassification.CONFIRMED_CORRECT);
        assertThat(evaluation.primaryReason()).isEqualTo(OutcomeReason.COMMUNITY_CLAIM_CONFIRMED);
    }

    @Test
    void expiredWithoutEvidenceIsClassifiedExplicitly() {
        ParkingSpotOutcomeContext context = new ParkingSpotOutcomeContext(
                SPOT_ID, ParkingSpotStatus.EXPIRED, BASE, BASE, BASE.plus(WINDOW), BASE.plus(WINDOW), 0, 0, 1.0);
        OutcomeEvidence evidence = OutcomeEvidenceFactory.fromContext(context, OutcomeTimeline.of(
                BASE, BASE.plus(WINDOW), List.of(
                        signal(OutcomeSignalType.PUBLISHED, BASE),
                        signal(OutcomeSignalType.TIME_EXPIRED, BASE.plus(WINDOW)))));
        OutcomeEvaluation evaluation = evaluate(evidence, BASE.plus(WINDOW).plusSeconds(1));
        assertThat(evaluation.classification()).isEqualTo(OutcomeClassification.EXPIRED_WITHOUT_EVIDENCE);
    }

    @Test
    void filledReportsReachConfirmedIncorrect() {
        ParkingSpotOutcomeContext context = new ParkingSpotOutcomeContext(
                SPOT_ID, ParkingSpotStatus.FILLED, BASE, BASE, BASE.plus(WINDOW), BASE.plus(Duration.ofMinutes(5)),
                0, 2, 0.3);
        OutcomeEvidence evidence = OutcomeEvidenceFactory.fromContextWithHistory(context, List.of(
                history("AI_PASSED", BASE),
                history("VERIFICATION_FILLED", BASE.plus(Duration.ofMinutes(2))),
                history("VERIFICATION_FILLED", BASE.plus(Duration.ofMinutes(3)))));
        OutcomeEvaluation evaluation = evaluate(evidence, BASE.plus(Duration.ofMinutes(6)));
        assertThat(evaluation.classification()).isEqualTo(OutcomeClassification.CONFIRMED_INCORRECT);
    }

    @Test
    void evaluationIsDeterministicForSameInputs() {
        OutcomeEvidence evidence = evidenceWithTimeline(List.of(
                signal(OutcomeSignalType.PUBLISHED, BASE),
                signal(OutcomeSignalType.VERIFICATION_AVAILABLE, BASE.plus(Duration.ofMinutes(2)))));
        Instant at = BASE.plus(Duration.ofMinutes(5));
        assertThat(evaluate(evidence, at)).isEqualTo(evaluate(evidence, at));
    }

    @Test
    void clockInjectionControlsValidationAge() {
        OutcomeEvidence evidence = evidenceWithTimeline(List.of(signal(OutcomeSignalType.PUBLISHED, BASE)));
        OutcomeEvaluation early = evaluate(evidence, BASE.plus(Duration.ofMinutes(2)));
        OutcomeEvaluation late = evaluate(evidence, BASE.plus(Duration.ofMinutes(8)));
        assertThat(early.validationAge()).isLessThan(late.validationAge());
        assertThat(early.validationWindowOpen()).isTrue();
    }

    private static OutcomeEvaluation evaluate(OutcomeEvidence evidence, Instant evaluatedAt) {
        OutcomeEvaluationContext context = new OutcomeEvaluationContext(
                evaluatedAt, OutcomePolicyConfig.POLICY_VERSION, WINDOW);
        return new OutcomeValidationEngine().evaluate(evidence, context);
    }

    private static OutcomeEvidence evidenceWithTimeline(List<OutcomeSignal> signals) {
        ParkingSpotOutcomeContext context = new ParkingSpotOutcomeContext(
                SPOT_ID, ParkingSpotStatus.VERIFIED, BASE, BASE, BASE.plus(WINDOW), BASE.plus(Duration.ofMinutes(5)),
                1, 0, 1.0);
        return OutcomeEvidenceFactory.fromContext(context, OutcomeTimeline.of(BASE, BASE.plus(WINDOW), signals));
    }

    private static OutcomeSignal signal(OutcomeSignalType type, Instant at) {
        return new OutcomeSignal(type, OutcomeSignalSource.COMMUNITY, at);
    }

    private static ParkingSpotStatusHistory history(String reason, Instant at) {
        return ParkingSpotStatusHistory.record(SPOT_ID, ParkingSpotStatus.ACTIVE, ParkingSpotStatus.SUSPICIOUS, reason, at);
    }
}