package com.parkio.parking.outcome.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.outcome.OutcomeClassification;
import org.junit.jupiter.api.Test;

class OutcomePolicyConfigTest {

    @Test
    void referenceConfigMapsClassificationToConfidence() {
        OutcomePolicyConfig config = OutcomePolicyConfig.referenceV1();
        assertThat(config.confidenceFor(OutcomeClassification.CONFIRMED_CORRECT))
                .isGreaterThan(config.confidenceFor(OutcomeClassification.LIKELY_CORRECT));
        assertThat(config.confidenceFor(OutcomeClassification.CONFIRMED_INCORRECT))
                .isGreaterThan(config.confidenceFor(OutcomeClassification.LIKELY_INCORRECT));
    }

    @Test
    void policyVersionIsStable() {
        assertThat(OutcomePolicyConfig.POLICY_VERSION.value()).isEqualTo("outcome-validation-v1");
    }
}