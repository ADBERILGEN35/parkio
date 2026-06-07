package com.parkio.media.domain.exception;

/** Stable, domain-level error codes for media operations (mapped to HTTP in presentation). */
public enum MediaErrorCode {
    MEDIA_NOT_FOUND,
    DUPLICATE_MEDIA,
    UNSUPPORTED_MEDIA_TYPE,
    FILE_TOO_LARGE,
    EMPTY_FILE,
    MISSING_USER_ID,
    NOT_MEDIA_OWNER
}
