package com.parkio.media.domain;

/**
 * Lifecycle of a media file.
 *
 * <p>An upload is only ever <em>servable</em> once it reaches {@link #READY}: signed
 * access URLs are issued for READY media only, and parking-spot creation accepts
 * READY media only. The malware scan is a precondition for READY — bytes that fail
 * or cannot be scanned never become servable (fail-closed).
 */
public enum MediaStatus {
    /**
     * Awaiting (or undergoing) the malware/safety scan; not yet servable. Under the
     * synchronous upload pipeline a row is created in this state in-memory and
     * transitions to {@link #READY} within the same transaction once the scan passes,
     * so it is rarely observed committed; it is the durable initial state for a future
     * asynchronous pipeline.
     */
    PENDING_SCAN,
    /** Passed all upload checks <em>including</em> the malware scan; servable. */
    READY,
    /** Rejected by validation or the malware scan (kept for audit; not persisted as a row today). */
    REJECTED,
    /** Soft-deleted; metadata retained, no longer served. */
    DELETED
}
