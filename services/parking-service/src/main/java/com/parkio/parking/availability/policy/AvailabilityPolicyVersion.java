package com.parkio.parking.availability.policy;

import java.util.Objects;

/**
 * Version token for availability policy configuration.
 */
public record AvailabilityPolicyVersion(String value) {

    public AvailabilityPolicyVersion {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static AvailabilityPolicyVersion of(String value) {
        return new AvailabilityPolicyVersion(value);
    }
}
