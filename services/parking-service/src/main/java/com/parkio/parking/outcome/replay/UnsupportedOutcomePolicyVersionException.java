package com.parkio.parking.outcome.replay;

import com.parkio.parking.outcome.policy.OutcomePolicyVersion;

public final class UnsupportedOutcomePolicyVersionException extends RuntimeException {

    private final OutcomePolicyVersion policyVersion;

    public UnsupportedOutcomePolicyVersionException(OutcomePolicyVersion policyVersion) {
        super("Unsupported outcome policy version: " + policyVersion.value());
        this.policyVersion = policyVersion;
    }

    public OutcomePolicyVersion policyVersion() {
        return policyVersion;
    }
}