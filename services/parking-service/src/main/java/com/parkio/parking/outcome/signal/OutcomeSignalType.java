package com.parkio.parking.outcome.signal;

/** Repository-backed post-publication signal categories. */
public enum OutcomeSignalType {

    PUBLISHED,
    VERIFICATION_AVAILABLE,
    VERIFICATION_FILLED,
    VERIFICATION_INVALID,
    VERIFICATION_ILLEGAL_OR_RISKY,
    VERIFICATION_WRONG_VEHICLE_SIZE,
    COMMUNITY_CLAIM,
    MODERATOR_APPROVED,
    MODERATOR_REJECTED,
    AI_PUBLISHED,
    AI_REJECTED,
    AI_PENDING_REVIEW,
    TIME_EXPIRED,
    REVIEW_FAILED
}