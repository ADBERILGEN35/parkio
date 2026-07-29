package com.parkio.parking.application;

/** Raised when a logical trust update was already durably recorded. */
public final class DuplicateTrustLedgerEntryException extends RuntimeException {

    public DuplicateTrustLedgerEntryException(String message) {
        super(message);
    }
}

