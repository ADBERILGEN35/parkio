package com.parkio.parking.application;

/** Raised when concurrent trust projection updates require a retry. */
public final class TrustShadowProjectionConflictException extends RuntimeException {

    public TrustShadowProjectionConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

