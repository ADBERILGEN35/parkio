package com.parkio.parking.availability;

/**
 * Bounded, replay-safe explanation codes for an availability evaluation.
 */
public enum AvailabilityReason {

    FRESH_PUBLICATION,
    TTL_REMAINING_HIGH,
    TTL_REMAINING_MODERATE,
    TTL_REMAINING_LOW,
    TIME_EXPIRED,
    STATUS_FILLED,
    STATUS_SUSPICIOUS,
    STATUS_EXPIRED,
    STATUS_REJECTED,
    STATUS_TERMINAL,
    STATUS_PENDING_MODERATION,
    NOT_PUBLISHED,
    COMMUNITY_VERIFIED,
    FILLED_REPORTS,
    LOW_CONFIDENCE,
    UNKNOWN_SIGNALS
}
