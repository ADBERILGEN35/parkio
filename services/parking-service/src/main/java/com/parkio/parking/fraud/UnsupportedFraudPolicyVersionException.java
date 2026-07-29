package com.parkio.parking.fraud;

/** Raised when an unknown fraud policy version is requested. */
public final class UnsupportedFraudPolicyVersionException extends RuntimeException {

    public UnsupportedFraudPolicyVersionException(String message) {
        super(message);
    }
}
