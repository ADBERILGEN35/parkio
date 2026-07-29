package com.parkio.parking.decision.calibration;

/**
 * Bounded primary rule that selected a shadow {@code PublicationDisposition}.
 * Distinct from free-form ReasonCode lists — safe for metric tags.
 */
public enum DecisivePolicyRule {
    HARD_MEDIA_MISMATCH,
    HARD_INVALID_COORDINATES,
    CRITICAL_NOT_PARKING,
    INSUFFICIENT_CONTENT,
    LEGALITY_CONCERN,
    UNRESOLVED_CONFLICT,
    HIGH_RISK,
    ELEVATED_RISK,
    LOW_RISK_COMPLETE,
    FALLBACK_HOLD,
    UNKNOWN
}
