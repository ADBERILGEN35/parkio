package com.parkio.parking.outcome.policy;

import java.util.Objects;

public record OutcomePolicyVersion(String value) {

    public OutcomePolicyVersion {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static OutcomePolicyVersion of(String value) {
        return new OutcomePolicyVersion(value);
    }
}