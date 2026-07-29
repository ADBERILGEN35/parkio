package com.parkio.parking.exposure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExposureEngineTest {

    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");
    private final ExposureEngine engine = new ExposureEngine();

    @Test
    void closerDistanceScoresHigher() {
        ExposureEvaluation near = evaluate(evidence(100));
        ExposureEvaluation far = evaluate(evidence(900));
        assertThat(near.score().total()).isGreaterThan(far.score().total());
    }

    @Test
    void unavailableCandidateIsIneligible() {
        ExposureEvaluation evaluation = engine.evaluate(
                evidenceBuilder()
                        .availabilityState(ExposureAvailabilityState.UNAVAILABLE)
                        .searchableVisible(false)
                        .build(),
                context());
        assertThat(evaluation.eligibility()).isEqualTo(ExposureEligibility.INELIGIBLE_NOT_PUBLISHED);
        assertThat(evaluation.disposition()).isEqualTo(ExposureDisposition.INELIGIBLE);
    }

    @Test
    void expiredCandidateIsIneligible() {
        ExposureEvaluation evaluation = engine.evaluate(
                evidenceBuilder()
                        .availabilityState(ExposureAvailabilityState.EXPIRED)
                        .searchableVisible(true)
                        .build(),
                context());
        assertThat(evaluation.eligibility()).isEqualTo(ExposureEligibility.INELIGIBLE_EXPIRED);
    }

    @Test
    void unknownTrustIsNeutralAndCapped() {
        ExposureEvaluation withoutTrust = evaluate(evidence(200));
        assertThat(withoutTrust.score().components())
                .anyMatch(component -> component.name().equals("TRUST") && component.contribution() == 0);
    }

    @Test
    void geospatialComponentDominatesOptionalTrustBand() {
        ExposureEvaluation near = evaluate(evidence(50));
        ExposureEvaluation far = evaluate(evidence(950));
        int distanceGap = component(near, "DISTANCE") - component(far, "DISTANCE");
        int trustGap = component(near, "TRUST") - component(far, "TRUST");
        assertThat(distanceGap).isGreaterThan(trustGap);
    }

    @Test
    void sameInputsProduceIdenticalResults() {
        ExposureEvidence evidence = evidence(250);
        ExposureEvaluation first = evaluate(evidence);
        ExposureEvaluation second = evaluate(evidence);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void unsupportedPolicyVersionFailsExplicitly() {
        assertThatThrownBy(() -> engine.evaluate(
                evidence(100),
                new ExposureEvaluationContext(NOW, "exposure-policy-v99", ExposureSnapshotSchemaVersion.V1)))
                .isInstanceOf(UnsupportedExposurePolicyVersionException.class);
    }

    @Test
    void totalScoreRemainsBounded() {
        ExposureEvaluation evaluation = evaluate(evidence(0));
        assertThat(evaluation.score().total()).isLessThanOrEqualTo(ExposurePolicyConfig.MAX_TOTAL_SCORE);
    }

    @Test
    void shadowOrderingIsDeterministic() {
        List<ExposureEvaluation> evaluations = List.of(
                evaluate(evidence(500)),
                evaluate(evidence(100)),
                evaluate(evidence(300)));
        List<ShadowExposurePosition> first = ExposureShadowOrdering.shadowOrder(evaluations);
        List<ShadowExposurePosition> second = ExposureShadowOrdering.shadowOrder(evaluations);
        assertThat(first).isEqualTo(second);
        assertThat(first.getFirst().candidateId()).isEqualTo(evaluations.get(1).evidence().candidateId());
    }

    private ExposureEvaluation evaluate(ExposureEvidence evidence) {
        return engine.evaluate(evidence, context());
    }

    private static ExposureEvaluationContext context() {
        return new ExposureEvaluationContext(NOW, ExposurePolicyConfig.POLICY_VERSION, ExposureSnapshotSchemaVersion.V1);
    }

    private static ExposureEvidence evidence(int distanceMeters) {
        return evidenceBuilder().distanceMeters(distanceMeters).build();
    }

    private static ExposureEvidenceBuilder evidenceBuilder() {
        return new ExposureEvidenceBuilder();
    }

    private static int component(ExposureEvaluation evaluation, String name) {
        return evaluation.score().components().stream()
                .filter(component -> component.name().equals(name))
                .findFirst()
                .orElseThrow()
                .contribution();
    }

    private static final class ExposureEvidenceBuilder {
        private int distanceMeters = 100;
        private ExposureAvailabilityState availabilityState = ExposureAvailabilityState.AVAILABLE;
        private boolean searchableVisible = true;

        ExposureEvidenceBuilder distanceMeters(int distanceMeters) {
            this.distanceMeters = distanceMeters;
            return this;
        }

        ExposureEvidenceBuilder availabilityState(ExposureAvailabilityState availabilityState) {
            this.availabilityState = availabilityState;
            return this;
        }

        ExposureEvidenceBuilder searchableVisible(boolean searchableVisible) {
            this.searchableVisible = searchableVisible;
            return this;
        }

        ExposureEvidence build() {
            return new ExposureEvidence(
                    new ExposureCandidateId(UUID.randomUUID()),
                    distanceMeters,
                    1_000,
                    ExposurePublicationQuality.VERIFIED,
                    availabilityState,
                    ExposureVehicleMatch.NOT_REQUESTED,
                    ExposureTrustLevel.UNKNOWN,
                    NOW.minusSeconds(60),
                    NOW.minusSeconds(60),
                    NOW.plusSeconds(600),
                    "FRESH",
                    "NEAR",
                    searchableVisible);
        }
    }
}
