package com.parkio.parking.application;

/** Raised when an append-only fraud ledger insert conflicts with an existing logical evaluation. */
public final class DuplicateFraudLedgerEntryException extends RuntimeException {

    public DuplicateFraudLedgerEntryException(String message) {
        super(message);
    }
}
