package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.decision.policy.ShadowDecisionPolicyConfig;
import org.junit.jupiter.api.Test;

class DecisionAuthoritySettingsTest {

    @Test
    void disabledDefaults() {
        DecisionAuthoritySettings s = DecisionAuthoritySettings.disabledDefaults();
        assertThat(s.enabled()).isFalse();
        assertThat(s.canaryPercentage()).isZero();
        assertThat(s.policyVersion()).isEqualTo(ShadowDecisionPolicyConfig.POLICY_VERSION.value());
    }

    @Test
    void rejectsUnsupportedPolicyVersion() {
        assertThatThrownBy(() -> new DecisionAuthoritySettings(true, 10, "unknown-policy"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidPercentage() {
        assertThatThrownBy(() -> new DecisionAuthoritySettings(true, -1, "decision-shadow-v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionAuthoritySettings(true, 101, "decision-shadow-v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}