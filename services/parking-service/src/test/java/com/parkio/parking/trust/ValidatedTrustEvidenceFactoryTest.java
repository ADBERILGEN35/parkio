package com.parkio.parking.trust;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidatedTrustEvidenceFactoryTest {

    @Test
    void mapsConfirmedCorrectReporterEvidenceAsEligiblePositive() {
        UUID reporterId = UUID.randomUUID();
        TrustEvidence evidence = ValidatedTrustEvidenceFactory.reporterEvidence(
                outcome(OutcomeClassification.CONFIRMED_CORRECT, OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS, 95),
                reporterId);

        assertThat(evidence.subject().type()).isEqualTo(TrustSubjectType.REPORTER);
        assertThat(evidence.subject().subjectId()).isEqualTo(reporterId);
        assertThat(evidence.domain()).isEqualTo(TrustDomain.PARKING_REPORT_ACCURACY);
        assertThat(evidence.attributionQuality()).isEqualTo(TrustEvidence.AttributionQuality.DIRECT);
        assertThat(evidence.eligibility()).isEqualTo(TrustEvidence.Eligibility.ELIGIBLE);
    }

    @Test
    void normalExpirationDoesNotPenalizeReporter() {
        TrustEvidence evidence = ValidatedTrustEvidenceFactory.reporterEvidence(
                outcome(OutcomeClassification.EXPIRED_WITHOUT_EVIDENCE, OutcomeReason.TIME_EXPIRED_NO_EVIDENCE, 30),
                UUID.randomUUID());

        assertThat(evidence.attributionQuality()).isEqualTo(TrustEvidence.AttributionQuality.AMBIGUOUS);
        assertThat(evidence.eligibility()).isNotEqualTo(TrustEvidence.Eligibility.ELIGIBLE);
    }

    @Test
    void deterministicEvidenceIdentityUsesOutcomeAndReporter() {
        UUID reporterId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        OutcomeHistoryRecord outcome = outcome(
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95);

        TrustEvidence first = ValidatedTrustEvidenceFactory.reporterEvidence(outcome, reporterId);
        TrustEvidence second = ValidatedTrustEvidenceFactory.reporterEvidence(outcome, reporterId);

        assertThat(first.evidenceId()).isEqualTo(second.evidenceId());
        assertThat(first.evidenceGroupId()).isEqualTo(outcome.recordId());
    }

    private static OutcomeHistoryRecord outcome(
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence) {
        Instant publishedAt = Instant.parse("2026-07-28T09:00:00Z");
        Instant evaluatedAt = Instant.parse("2026-07-28T10:00:00Z");
        UUID spotId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        OutcomePolicyVersion policyVersion = OutcomePolicyVersion.of("outcome-policy-v1");
        OutcomeTimeline timeline = OutcomeTimeline.of(publishedAt, publishedAt.plus(Duration.ofMinutes(10)), List.of());
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
                        0,
                        0,
                        0.9,
                        timeline),
                new OutcomeEvaluationContext(evaluatedAt, policyVersion, Duration.ofMinutes(10)),
                evaluation);
        return new OutcomeHistoryRecord(
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                spotId,
                policyVersion,
                "outcome-snapshot-v1",
                OutcomeEvaluationTrigger.PUBLICATION,
                UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
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
