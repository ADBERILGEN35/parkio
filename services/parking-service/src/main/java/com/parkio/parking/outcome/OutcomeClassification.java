package com.parkio.parking.outcome;

/**
 * Historical quality classification for a published parking report.
 *
 * <p>Distinct from {@code PublicationDisposition} and {@code AvailabilityState}.
 */
public enum OutcomeClassification {

    CONFIRMED_CORRECT,
    LIKELY_CORRECT,
    UNKNOWN,
    LIKELY_INCORRECT,
    CONFIRMED_INCORRECT,
    EXPIRED_WITHOUT_EVIDENCE
}