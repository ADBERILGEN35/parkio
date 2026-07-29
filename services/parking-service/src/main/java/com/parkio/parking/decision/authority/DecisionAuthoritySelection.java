package com.parkio.parking.decision.authority;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Immutable result of authority configuration + deterministic canary selection
 * for one evaluation (before DecisionEngine execution).
 */
public final class DecisionAuthoritySelection {

    private final boolean globallyEnabled;
    private final boolean selected;
    private final AuthorityEligibilityReason reason;
    private final String policyVersion;
    private final String algorithmVersion;
    private final Integer canaryBucket;
    private final int canaryPercentage;

    private DecisionAuthoritySelection(
            boolean globallyEnabled,
            boolean selected,
            AuthorityEligibilityReason reason,
            String policyVersion,
            String algorithmVersion,
            Integer canaryBucket,
            int canaryPercentage) {
        this.globallyEnabled = globallyEnabled;
        this.selected = selected;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
        this.algorithmVersion = Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        this.canaryBucket = canaryBucket;
        this.canaryPercentage = canaryPercentage;
        if (selected && reason != AuthorityEligibilityReason.ELIGIBLE_SELECTED) {
            throw new IllegalArgumentException("selected requires ELIGIBLE_SELECTED");
        }
        if (!selected && reason == AuthorityEligibilityReason.ELIGIBLE_SELECTED) {
            throw new IllegalArgumentException("ELIGIBLE_SELECTED requires selected=true");
        }
    }

    public static DecisionAuthoritySelection of(
            boolean globallyEnabled,
            boolean selected,
            AuthorityEligibilityReason reason,
            String policyVersion,
            String algorithmVersion,
            Integer canaryBucket,
            int canaryPercentage) {
        return new DecisionAuthoritySelection(
                globallyEnabled,
                selected,
                reason,
                policyVersion,
                algorithmVersion,
                canaryBucket,
                canaryPercentage);
    }

    public boolean globallyEnabled() {
        return globallyEnabled;
    }

    public boolean selected() {
        return selected;
    }

    public AuthorityEligibilityReason reason() {
        return reason;
    }

    public String policyVersion() {
        return policyVersion;
    }

    public String algorithmVersion() {
        return algorithmVersion;
    }

    public OptionalInt canaryBucket() {
        return canaryBucket == null ? OptionalInt.empty() : OptionalInt.of(canaryBucket);
    }

    public int canaryPercentage() {
        return canaryPercentage;
    }
}