package com.parkio.media.domain.exception;

/** Stable, domain-level error codes for media operations (mapped to HTTP in presentation). */
public enum MediaErrorCode {
    MEDIA_NOT_FOUND,
    DUPLICATE_MEDIA,
    UNSUPPORTED_MEDIA_TYPE,
    FILE_TOO_LARGE,
    EMPTY_FILE,
    /** The image bytes could not be decoded or exceeded safe pixel/dimension limits. */
    INVALID_IMAGE,
    MISSING_USER_ID,
    NOT_MEDIA_OWNER,
    /** The uploaded bytes failed the malware scan (mapped to 422). */
    MEDIA_INFECTED,
    /** The malware scan could not be completed; upload fails closed (mapped to 503). */
    MEDIA_SCAN_UNAVAILABLE
}
