package com.parkio.parking.fraud;

/** Raised when an unknown fraud schema version is requested. */
public final class UnsupportedFraudSchemaVersionException extends RuntimeException {

    public UnsupportedFraudSchemaVersionException(String message) {
        super(message);
    }
}
