package com.parkio.parking.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.assessment.AssessmentCategory;
import com.parkio.parking.decision.assessment.AssessmentCompleteness;
import com.parkio.parking.decision.assessment.AssessmentLevel;
import com.parkio.parking.decision.calibration.AssessmentCategorySnapshot;
import com.parkio.parking.decision.calibration.DecisionCalibrationObservation;
import com.parkio.parking.decision.calibration.DecisivePolicyRule;
import com.parkio.parking.decision.calibration.EvidenceAvailabilityProfile;
import com.parkio.parking.decision.calibration.HardConstraintFamily;
import com.parkio.parking.decision.calibration.RiskBand;
import com.parkio.parking.decision.calibration.ShadowFailureStage;
import com.parkio.parking.decision.shadow.LegacyPublicationOutcome;
import com.parkio.parking.decision.shadow.ShadowComparisonCategory;
import com.parkio.parking.domain.ParkingSpotStatus;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DecisionShadowMetricsTest {

    @Test
    void successEmitsBoundedTagFamiliesOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionShadowMetrics metrics = new DecisionShadowMetrics(registry);

        metrics.recordAttempt();
        metrics.recordSuccess(sampleObservation());

        Set<String> tagKeys = registry.getMeters().stream()
                .flatMap(m -> m.getId().getTags().stream())
                .map(t -> t.getKey())
                .collect(Collectors.toSet());
        assertThat(tagKeys)
                .contains("policy_version", "disposition", "category", "band", "family", "rule", "profile", "kind", "status", "level", "completeness")
                .doesNotContain("spotId", "eventId", "reason", "risk_score", "userId");

        for (Meter meter : registry.getMeters()) {
            for (var tag : meter.getId().getTags()) {
                assertThat(tag.getKey())
                        .isNotIn("spotId", "eventId", "mediaId", "userId", "reason", "risk_score");
                assertThat(tag.getValue()).doesNotContain("aaaaaaaa-bbbb");
            }
        }

        assertThat(registry.find("parkio.parking.decision.shadow.success").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("parkio.parking.decision.shadow.disposition")
                        .tag("disposition", "FULL_PUBLISH")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.find("parkio.parking.decision.shadow.risk_band")
                        .tag("band", "LOW")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void failureEmitsExactlyOneStage() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionShadowMetrics metrics = new DecisionShadowMetrics(registry);
        metrics.recordAttempt();
        metrics.recordFailure(ShadowFailureStage.EVIDENCE_COLLECTION, Duration.ofMillis(5));

        assertThat(registry.find("parkio.parking.decision.shadow.failure")
                        .tag("stage", "EVIDENCE_COLLECTION")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        double otherStages = 0;
        for (ShadowFailureStage stage : ShadowFailureStage.values()) {
            if (stage == ShadowFailureStage.EVIDENCE_COLLECTION) {
                continue;
            }
            otherStages += registry.find("parkio.parking.decision.shadow.failure")
                    .tag("stage", stage.name())
                    .counter()
                    .count();
        }
        assertThat(otherStages).isZero();
    }

    @Test
    void rejectsUnknownPolicyVersion() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionShadowMetrics metrics = new DecisionShadowMetrics(registry);
        DecisionCalibrationObservation bad = DecisionCalibrationObservation.of(
                "other-policy",
                LegacyPublicationOutcome.Kind.APPLIED,
                ParkingSpotStatus.ACTIVE,
                PublicationDisposition.HOLD,
                ShadowComparisonCategory.EQUIVALENT,
                RiskBand.LOW,
                HardConstraintFamily.NONE,
                DecisivePolicyRule.FALLBACK_HOLD,
                EvidenceAvailabilityProfile.AI_ONLY,
                List.of(),
                Duration.ZERO,
                Instant.parse("2026-07-27T12:00:00Z"));
        assertThatThrownBy(() -> metrics.recordSuccess(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    private static DecisionCalibrationObservation sampleObservation() {
        return DecisionCalibrationObservation.of(
                "decision-shadow-v1",
                LegacyPublicationOutcome.Kind.APPLIED,
                ParkingSpotStatus.ACTIVE,
                PublicationDisposition.FULL_PUBLISH,
                ShadowComparisonCategory.EQUIVALENT,
                RiskBand.LOW,
                HardConstraintFamily.NONE,
                DecisivePolicyRule.LOW_RISK_COMPLETE,
                EvidenceAvailabilityProfile.COMPLETE_CURRENT_V1,
                List.of(new AssessmentCategorySnapshot(
                        AssessmentCategory.CONTENT,
                        AssessmentLevel.POSITIVE,
                        AssessmentCompleteness.COMPLETE)),
                Duration.ofMillis(4),
                Instant.parse("2026-07-27T12:00:00Z"));
    }
}