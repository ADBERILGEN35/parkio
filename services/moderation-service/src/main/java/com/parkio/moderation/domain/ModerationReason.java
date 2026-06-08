package com.parkio.moderation.domain;

/**
 * Why a target was reported / a case opened. Each reason maps to a default
 * {@link ModerationSeverity}, and the "serious" reasons open a case immediately.
 */
public enum ModerationReason {
    FAKE_PHOTO,
    DUPLICATE_PHOTO,
    OLD_PHOTO,
    WRONG_LOCATION,
    NOT_A_PARKING_SPOT,
    ILLEGAL_OR_RISKY,
    WRONG_VEHICLE_SIZE,
    PRIVATE_PROPERTY,
    SPAM_BEHAVIOR,
    ABUSE_REPORT;

    /** Serious reasons open a moderation case immediately (no threshold). */
    public boolean isSerious() {
        return this == ILLEGAL_OR_RISKY || this == FAKE_PHOTO
                || this == PRIVATE_PROPERTY || this == ABUSE_REPORT;
    }

    public ModerationSeverity defaultSeverity() {
        return switch (this) {
            case ILLEGAL_OR_RISKY -> ModerationSeverity.CRITICAL;
            case FAKE_PHOTO, PRIVATE_PROPERTY, ABUSE_REPORT -> ModerationSeverity.HIGH;
            case NOT_A_PARKING_SPOT, WRONG_LOCATION, SPAM_BEHAVIOR -> ModerationSeverity.MEDIUM;
            case DUPLICATE_PHOTO, OLD_PHOTO, WRONG_VEHICLE_SIZE -> ModerationSeverity.LOW;
        };
    }
}
