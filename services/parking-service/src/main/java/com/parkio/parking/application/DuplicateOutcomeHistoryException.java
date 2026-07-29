package com.parkio.parking.application;

/** Signals that an outcome history record already exists for the same deterministic evaluation. */
public class DuplicateOutcomeHistoryException extends RuntimeException {

    public DuplicateOutcomeHistoryException(String message) {
        super(message);
    }
}