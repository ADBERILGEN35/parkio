package com.parkio.parking.application;

import com.parkio.parking.decision.authority.AuthorityCanarySelector;
import com.parkio.parking.decision.policy.ShadowDecisionPolicyConfig;
import java.util.Objects;

/** Immutable authority configuration snapshot used by application orchestration. */
public final class DecisionAuthoritySettings {

    private final boolean enabled;
    private final int canaryPercentage;
    private final String policyVersion;

    public DecisionAuthoritySettings(boolean enabled, int canaryPercentage, String policyVersion) {
        AuthorityCanarySelector.requirePercentage(canaryPercentage);
        this.enabled = enabled;
        this.canaryPercentage = canaryPercentage;
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion").trim();
        if (this.policyVersion.isEmpty() || this.policyVersion.length() > 64) {
            throw new IllegalArgumentException("policyVersion must be 1..64 characters");
        }
        if (!ShadowDecisionPolicyConfig.POLICY_VERSION.value().equals(this.policyVersion)) {
            throw new IllegalArgumentException("Unsupported authority policy version: " + this.policyVersion);
        }
    }

    public static DecisionAuthoritySettings disabledDefaults() {
        return new DecisionAuthoritySettings(false, 0, ShadowDecisionPolicyConfig.POLICY_VERSION.value());
    }

    public boolean enabled() {
        return enabled;
    }

    public int canaryPercentage() {
        return canaryPercentage;
    }

    public String policyVersion() {
        return policyVersion;
    }
}