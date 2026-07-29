package com.parkio.parking.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.outcome.OutcomeSnapshot;
import com.parkio.parking.outcome.confidence.OutcomeConfidence;
import com.parkio.parking.outcome.evaluation.OutcomeEvaluationContext;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;
import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;
import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import com.parkio.parking.outcome.policy.OutcomePolicyVersion;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustEngineTest {

    private final TrustEngine engine = new TrustEngine();

    @Test
    void coldStartIsNeutralWithLowConfidence() {
        TrustEvaluationContext context = contextAt("2026-07-28T10:00:00Z");

        TrustSnapshot snapshot = engine.initialSnapshot(subject(), TrustDomain.PARKING_REPORT_ACCURACY, context);

        assertThat(snapshot.score().basisPoints()).isEqualTo(5_000);
        assertThat(snapshot.confidence().basisPoints()).isZero();
        assertThat(snapshot.effectiveEvidenceCount()).isZero();
        assertThat(snapshot.level()).isEqualTo(TrustSnapshot.Level.UNKNOWN);
    }

    @Test
    void likelyCorrectHasLessImpactThanConfirmedCorrect() {
        TrustSnapshot initial = engine.initialSnapshot(subject(), TrustDomain.PARKING_REPORT_ACCURACY, contextAt("2026-07-28T10:00:00Z"));

        TrustEvaluation likely = engine.evaluate(
                initial,
                ValidatedTrustEvidenceFactory.reporterEvidence(outcome(OutcomeClassification.LIKELY_CORRECT, OutcomeReason.SINGLE_AVAILABLE_VERIFICATION, 80), subject().subjectId()),
                contextAt("2026-07-28T10:00:00Z"));
        TrustEvaluation confirmed = engine.evaluate(
                initial,
                ValidatedTrustEvidenceFactory.reporterEvidence(outcome(OutcomeClassification.CONFIRMED_CORRECT, OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS, 90), subject().subjectId()),
                contextAt("2026-07-28T10:05:00Z"));

        assertThat(confirmed.resultingSnapshot().score().basisPoints())
                .isGreaterThan(likely.resultingSnapshot().score().basisPoints());
        assertThat(confirmed.positiveEvidenceDelta()).isGreaterThan(likely.positiveEvidenceDelta());
    }

    @Test
    void ambiguousOutcomesRemainNeutral() {
        TrustSnapshot initial = engine.initialSnapshot(subject(), TrustDomain.PARKING_REPORT_ACCURACY, contextAt("2026-07-28T10:00:00Z"));
        TrustEvidence evidence = ValidatedTrustEvidenceFactory.reporterEvidence(
                outcome(OutcomeClassification.CONFIRMED_INCORRECT, OutcomeReason.COMMUNITY_FILLED_REPORTS, 95),
                subject().subjectId());

        TrustEvaluation evaluation = engine.evaluate(initial, evidence, contextAt("2026-07-28T10:00:00Z"));

        assertThat(evidence.eligibility()).isEqualTo(TrustEvidence.Eligibility.AMBIGUOUS_OUTCOME);
        assertThat(evaluation.direction()).isEqualTo(TrustEvaluation.Direction.NEUTRAL);
        assertThat(evaluation.resultingSnapshot().score()).isEqualTo(initial.score());
    }

    @Test
    void scoreAndConfidenceStayBounded() {
        TrustSnapshot snapshot = engine.initialSnapshot(subject(), TrustDomain.PARKING_REPORT_ACCURACY, contextAt("2026-07-28T10:00:00Z"));

        for (int i = 0; i < 50; i++) {
            snapshot = engine.evaluate(
                            snapshot,
                            ValidatedTrustEvidenceFactory.reporterEvidence(
                                    outcome(OutcomeClassification.CONFIRMED_CORRECT, OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS, 100),
                                    subject().subjectId()),
                            contextAt(Instant.parse("2026-07-28T10:00:00Z").plusSeconds(i).toString()))
                    .resultingSnapshot();
        }

        assertThat(snapshot.score().basisPoints()).isBetween(0, 10_000);
        assertThat(snapshot.confidence().basisPoints()).isBetween(0, 10_000);
    }

    @Test
    void policyValidationRejectsNonMonotonicThresholds() {
        assertThatThrownBy(() -> new TrustPolicyConfig(
                "bad",
                100,
                100,
                100,
                10,
                5,
                10,
                5,
                10,
                70,
                10_000,
                8_000,
                4_000,
                10_000,
                8_500,
                6_500,
                7_000,
                6_000,
                8_500,
                8)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownPolicyVersionFailsExplicitly() {
        TrustSnapshot initial = engine.initialSnapshot(subject(), TrustDomain.PARKING_REPORT_ACCURACY, contextAt("2026-07-28T10:00:00Z"));

        assertThatThrownBy(() -> engine.evaluate(
                initial,
                ValidatedTrustEvidenceFactory.reporterEvidence(
                        outcome(OutcomeClassification.CONFIRMED_CORRECT, OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS, 90),
                        subject().subjectId()),
                new TrustEvaluationContext(Instant.parse("2026-07-28T10:00:00Z"), "trust-policy-v999", TrustSnapshotSchemaVersion.V1)))
                .isInstanceOf(UnsupportedTrustPolicyVersionException.class);
    }

    private static TrustSubject subject() {
        return new TrustSubject(TrustSubjectType.REPORTER, UUID.fromString("11111111-1111-1111-1111-111111111111"));
    }

    private static TrustEvaluationContext contextAt(String timestamp) {
        return contextAt(Instant.parse(timestamp));
    }

    private static TrustEvaluationContext contextAt(Instant timestamp) {
        return new TrustEvaluationContext(timestamp, TrustPolicyConfig.POLICY_VERSION, TrustSnapshotSchemaVersion.V1);
    }

    private static OutcomeHistoryRecord outcome(
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence) {
        Instant publishedAt = Instant.parse("2026-07-28T09:00:00Z");
        Instant evaluatedAt = Instant.parse("2026-07-28T10:00:00Z");
        UUID spotId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        OutcomePolicyVersion policyVersion = OutcomePolicyVersion.of("outcome-policy-v1");
        OutcomeTimeline timeline = OutcomeTimeline.of(publishedAt, publishedAt.plus(Duration.ofMinutes(10)), java.util.List.of());
        OutcomeEvaluation evaluation = new OutcomeEvaluation(
                spotId,
                classification,
                OutcomeConfidence.of(confidence),
                reason,
                Set.of(reason),
                timeline,
                Duration.ofMinutes(60),
                false,
                policyVersion,
                evaluatedAt);
        OutcomeSnapshot snapshot = new OutcomeSnapshot(
                new OutcomeEvidence(
                        spotId,
                        ParkingSpotStatus.ACTIVE,
                        publishedAt.minusSeconds(30),
                        publishedAt,
                        publishedAt.plus(Duration.ofMinutes(10)),
                        evaluatedAt,
                        1,
                        0,
                        0.9,
                        timeline),
                new OutcomeEvaluationContext(evaluatedAt, policyVersion, Duration.ofMinutes(10)),
                evaluation);
        return new OutcomeHistoryRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                spotId,
                policyVersion,
                "outcome-snapshot-v1",
                OutcomeEvaluationTrigger.PUBLICATION,
                UUID.randomUUID(),
                evaluatedAt,
                evaluatedAt,
                snapshot,
                classification,
                OutcomeConfidence.of(confidence),
                reason,
                false,
                evaluatedAt);
    }
}
