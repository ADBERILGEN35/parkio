package com.parkio.parking.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudEngineTest {

    private final FraudEngine engine = new FraudEngine();

    @Test
    void noHistoryProducesInsufficientEvidence() {
        FraudFeatureVector features = features(0, 0, 0, 0, 0, 0);
        FraudEvaluation evaluation = engine.evaluate(features, contextAt("2026-07-28T10:00:00Z"));

        assertThat(evaluation.disposition()).isEqualTo(FraudDisposition.INSUFFICIENT_EVIDENCE);
        assertThat(evaluation.riskBand()).isEqualTo(FraudRiskBand.MINIMAL);
        assertThat(evaluation.confidenceBand()).isEqualTo(FraudConfidenceBand.NONE);
    }

    @Test
    void oneConfirmedIncorrectDoesNotProduceCriticalRisk() {
        FraudFeatureVector features = features(1, 1, 0, 0, 0, 0);
        FraudEvaluation evaluation = engine.evaluate(features, contextAt("2026-07-28T10:00:00Z"));

        assertThat(evaluation.riskBand()).isNotEqualTo(FraudRiskBand.CRITICAL);
        assertThat(evaluation.disposition()).isIn(FraudDisposition.OBSERVE, FraudDisposition.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void repeatedDirectConfirmedIncorrectIncreasesRisk() {
        FraudFeatureVector low = features(2, 1, 0, 0, 0, 0);
        FraudFeatureVector high = features(4, 4, 0, 0, 0, 0);

        FraudEvaluation lowEval = engine.evaluate(low, contextAt("2026-07-28T10:00:00Z"));
        FraudEvaluation highEval = engine.evaluate(high, contextAt("2026-07-28T10:05:00Z"));

        assertThat(highEval.riskScore().basisPoints()).isGreaterThan(lowEval.riskScore().basisPoints());
        assertThat(highEval.hardAnomaly()).contains(FraudHardAnomalyType.REPEATED_DIRECT_CONFIRMED_INCORRECT);
    }

    @Test
    void unknownAndExpiredAreNeutral() {
        FraudFeatureVector features = features(2, 0, 0, 0, 1, 1);
        FraudEvaluation evaluation = engine.evaluate(features, contextAt("2026-07-28T10:00:00Z"));

        assertThat(evaluation.riskBand()).isEqualTo(FraudRiskBand.MINIMAL);
        assertThat(evaluation.disposition()).isEqualTo(FraudDisposition.NO_SIGNAL);
    }

    @Test
    void riskConfidenceAndEvidenceVolumeRemainSeparate() {
        FraudFeatureVector sparse = features(2, 2, 0, 0, 0, 0);
        FraudFeatureVector dense = features(8, 4, 0, 4, 0, 0);

        FraudEvaluation sparseEval = engine.evaluate(sparse, contextAt("2026-07-28T10:00:00Z"));
        FraudEvaluation denseEval = engine.evaluate(dense, contextAt("2026-07-28T10:05:00Z"));

        assertThat(denseEval.confidenceBand()).isNotEqualTo(FraudConfidenceBand.LOW);
        assertThat(sparseEval.evidenceVolume().count()).isLessThan(denseEval.evidenceVolume().count());
    }

    @Test
    void unknownPolicyVersionFailsExplicitly() {
        FraudFeatureVector features = features(1, 0, 0, 1, 0, 0);
        FraudEvaluationContext context = new FraudEvaluationContext(
                Instant.parse("2026-07-28T10:00:00Z"),
                "fraud-policy-v99",
                FraudSnapshotSchemaVersion.V1,
                "fraud-mapping-v1");

        assertThatThrownBy(() -> engine.evaluate(features, context))
                .isInstanceOf(UnsupportedFraudPolicyVersionException.class);
    }

    @Test
    void engineIsDeterministic() {
        FraudFeatureVector features = features(3, 2, 1, 1, 0, 0);
        FraudEvaluationContext context = contextAt("2026-07-28T10:00:00Z");

        FraudEvaluation first = engine.evaluate(features, context);
        FraudEvaluation second = engine.evaluate(features, context);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void invalidPolicyConfigFailsAtConstruction() {
        assertThatThrownBy(() -> new FraudPolicyConfig(
                FraudPolicyConfig.POLICY_VERSION,
                1,
                2,
                4,
                900,
                2_500,
                400,
                6_000,
                2_500,
                8_500,
                1_800,
                2_000,
                4_500,
                7_000,
                2,
                5)).isInstanceOf(IllegalArgumentException.class);
    }

    private static FraudFeatureVector features(
            int eligible,
            int directIncorrect,
            int likelyIncorrect,
            int confirmedCorrect,
            int unknown,
            int expired) {
        Instant start = Instant.parse("2026-07-21T10:00:00Z");
        Instant end = Instant.parse("2026-07-28T10:00:00Z");
        UUID reporter = UUID.fromString("11111111-1111-1111-1111-111111111111");
        return new FraudFeatureVector(
                new FraudSubject(FraudSubjectType.USER, reporter),
                FraudDomain.CONTRIBUTION_INTEGRITY,
                start,
                end,
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                end,
                eligible,
                directIncorrect,
                likelyIncorrect,
                confirmedCorrect,
                unknown,
                expired,
                FraudAggregationVersion.V1);
    }

    private static FraudEvaluationContext contextAt(String instant) {
        return new FraudEvaluationContext(
                Instant.parse(instant),
                FraudPolicyConfig.POLICY_VERSION,
                FraudSnapshotSchemaVersion.V1,
                "fraud-mapping-v1");
    }
}
