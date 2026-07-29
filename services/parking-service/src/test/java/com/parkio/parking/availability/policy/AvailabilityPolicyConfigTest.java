package com.parkio.parking.availability.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AvailabilityPolicyConfigTest {

    @Test
    void referenceConfigUsesMonotonicDecayThresholds() {
        AvailabilityPolicyConfig config = AvailabilityPolicyConfig.referenceV1();
        assertThat(config.policyVersion()).isEqualTo(AvailabilityPolicyConfig.POLICY_VERSION);
        assertThat(AvailabilityPolicyConfig.AVAILABLE_REMAINING_BPS)
                .isGreaterThan(AvailabilityPolicyConfig.LIKELY_AVAILABLE_REMAINING_BPS);
        assertThat(AvailabilityPolicyConfig.LIKELY_AVAILABLE_REMAINING_BPS)
                .isGreaterThan(AvailabilityPolicyConfig.UNKNOWN_REMAINING_BPS);
    }

    @Test
    void divideHalfUpMatchesIntegerExpectations() {
        assertThat(AvailabilityPolicyConfig.divideHalfUp(5, 2)).isEqualTo(3);
        assertThat(AvailabilityPolicyConfig.divideHalfUp(4, 2)).isEqualTo(2);
        assertThat(AvailabilityPolicyConfig.divideHalfUp(-5, 2)).isEqualTo(-3);
    }

    @Test
    void clampScoreBoundsValues() {
        assertThat(AvailabilityPolicyConfig.clampScore(-1)).isZero();
        assertThat(AvailabilityPolicyConfig.clampScore(150)).isEqualTo(100);
        assertThat(AvailabilityPolicyConfig.clampScore(42)).isEqualTo(42);
    }
}