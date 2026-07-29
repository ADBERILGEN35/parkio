package com.parkio.parking.availability.evidence;

/**
 * Normalized signal categories supported by the current parking aggregate.
 */
public enum AvailabilityEvidenceType {

    /** Spot lifecycle status at evaluation time. */
    STATUS,

    /** Whether the spot has entered its advertised lifetime. */
    PUBLICATION_LIFETIME,

    /** Remaining and elapsed TTL window. */
    TTL_REMAINING,

    /** Age since the underlying report was created. */
    REPORT_AGE,

    /** Community verification count. */
    VERIFICATION,

    /** Community filled reports before terminal fill. */
    FILLED_REPORT,

    /** Aggregate confidence score from negative signals. */
    CONFIDENCE
}
