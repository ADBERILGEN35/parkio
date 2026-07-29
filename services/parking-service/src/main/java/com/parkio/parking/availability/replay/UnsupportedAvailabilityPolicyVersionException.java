package com.parkio.parking.availability.replay;

import com.parkio.parking.availability.policy.AvailabilityPolicyVersion;

/**
 * Raised when an availability snapshot references an unsupported policy version.
 */
public final class UnsupportedAvailabilityPolicyVersionException extends RuntimeException {

    private final AvailabilityPolicyVersion policyVersion;

    public UnsupportedAvailabilityPolicyVersionException(AvailabilityPolicyVersion policyVersion) {
        super("Unsupported availability policy version: " + policyVersion.value());
        this.policyVersion = policyVersion;
    }

    public AvailabilityPolicyVersion policyVersion() {
        return policyVersion;
    }
}
