package com.parkio.parking.decision.audit;

/**
 * Raised when offline replay cannot resolve a persisted policy or engine version.
 */
public final class UnsupportedDecisionVersionException extends IllegalArgumentException {

    public UnsupportedDecisionVersionException(String message) {
        super(message);
    }
}