package com.parkio.parking.domain;

/**
 * Internal analytics / event provenance for how a session ended.
 * Public API still exposes coarse {@link ParkingSessionCompletionType} (MANUAL/AUTO).
 */
public enum ParkingSessionCompletionReason {
    MANUAL,
    AUTO_TIMEOUT,
    USER_CONFIRMATION,
    ADMIN,
    SYSTEM,
    API,
    MIGRATION;

    /** Maps to the public wire completionType without breaking clients. */
    public ParkingSessionCompletionType toCompletionType() {
        return this == AUTO_TIMEOUT
                ? ParkingSessionCompletionType.AUTO
                : ParkingSessionCompletionType.MANUAL;
    }

    public static ParkingSessionCompletionReason fromLegacyType(ParkingSessionCompletionType type) {
        if (type == null) {
            return null;
        }
        return type == ParkingSessionCompletionType.AUTO ? AUTO_TIMEOUT : MANUAL;
    }
}