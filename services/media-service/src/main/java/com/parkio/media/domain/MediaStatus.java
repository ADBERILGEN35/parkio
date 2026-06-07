package com.parkio.media.domain;

/** Lifecycle of a media file. */
public enum MediaStatus {
    /** Stored in object storage; metadata persisted. */
    UPLOADED,
    /** Passed the synchronous validations (size, mime, duplicate). */
    VALIDATED,
    /** Failed validation (kept for audit; not currently persisted as a row). */
    REJECTED,
    /** Soft-deleted; metadata retained, no longer served. */
    DELETED
}
