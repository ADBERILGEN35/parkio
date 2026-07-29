package com.parkio.parking.decision.audit;

import com.parkio.parking.decision.policy.DecisionEngine;
import com.parkio.parking.decision.policy.ShadowDecisionPolicyConfig;
import java.util.Objects;

/**
 * Resolves a {@link DecisionEngine} for an explicit policy / engine version pair.
 *
 * <p>Does not auto-migrate versions. Unknown versions fail closed.
 */
public final class DecisionEngineFactory {

    private DecisionEngineFactory() {}

    public static DecisionEngine forVersions(String policyVersion, String decisionEngineVersion) {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(decisionEngineVersion, "decisionEngineVersion");
        requireEngineVersion(decisionEngineVersion);
        requirePolicyVersion(policyVersion);
        return new DecisionEngine();
    }

    public static void requirePolicyVersion(String policyVersion) {
        if (!ShadowDecisionPolicyConfig.POLICY_VERSION.value().equals(policyVersion)) {
            throw new UnsupportedDecisionVersionException(
                    "Unsupported policy version for replay: " + policyVersion);
        }
    }

    public static void requireEngineVersion(String decisionEngineVersion) {
        if (!DecisionEngineVersion.isKnown(decisionEngineVersion)) {
            throw new UnsupportedDecisionVersionException(
                    "Unsupported decision engine version for replay: " + decisionEngineVersion);
        }
    }
}