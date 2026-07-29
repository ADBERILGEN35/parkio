package com.parkio.parking.trust;

/** Raised when replay or evaluation requests an unknown trust policy version. */
public final class UnsupportedTrustPolicyVersionException extends IllegalArgumentException {

    public UnsupportedTrustPolicyVersionException(String message) {
        super(message);
    }
}

