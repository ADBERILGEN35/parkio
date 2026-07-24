package com.parkio.parking.domain.exception;

/** Stable, domain-level error codes for parking operations (mapped to HTTP in presentation). */
public enum ParkingErrorCode {
    SPOT_NOT_FOUND,
    ILLEGAL_SPOT_REJECTED,
    OWNER_CANNOT_VERIFY,
    OWNER_CANNOT_CLAIM,
    ALREADY_VERIFIED,
    SPOT_NOT_VERIFIABLE,
    SPOT_NOT_CLAIMABLE,
    SPOT_EXPIRED,
    PARKING_SESSION_NOT_FOUND,
    PARKING_SESSION_NOT_ACTIVE,
    ACTIVE_PARKING_SESSION_EXISTS,
    INVALID_PARKING_SESSION_CURSOR,
    MISSING_USER_ID,
    MEDIA_ACCESS_UNAVAILABLE,
    /** The referenced media is missing or has not passed media-service safety checks (not READY). */
    MEDIA_NOT_READY
}
