package com.parkio.parking.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.assessment.AssessmentCategory;
import com.parkio.parking.decision.assessment.AssessmentLevel;
import org.junit.jupiter.api.Test;

class CategoryAssessmentEvaluatorTest {

    private final DefaultEvidenceEvaluationPolicy policy = new DefaultEvidenceEvaluationPolicy();

    @Test
    void contentEvaluatorMarksNotParkingCritical() {
        AssessmentBundle bundle = policy.evaluate(
                DecisionGoldenFixtures.vectorWithNotParking(), DecisionGoldenFixtures.context());
        assertThat(bundle.find(AssessmentCategory.CONTENT).orElseThrow().level())
                .isEqualTo(AssessmentLevel.CRITICAL);
    }

    @Test
    void hardConstraintPolicyDetectsMediaMismatchAndInvalidCoordinates() {
        HardConstraintPolicy hard = new HardConstraintPolicy(ShadowDecisionPolicyConfig.referenceV1());
        AssessmentBundle mismatch = policy.evaluate(
                DecisionGoldenFixtures.mediaMismatch(), DecisionGoldenFixtures.context());
        AssessmentBundle invalid = policy.evaluate(
                DecisionGoldenFixtures.invalidCoordinates(), DecisionGoldenFixtures.context());

        assertThat(hard.evaluate(mismatch).active()).isTrue();
        assertThat(hard.evaluate(invalid).active()).isTrue();
        assertThat(hard.evaluate(policy.evaluate(
                        DecisionGoldenFixtures.strongNormal(), DecisionGoldenFixtures.context()))
                .active())
                .isFalse();
    }
}
