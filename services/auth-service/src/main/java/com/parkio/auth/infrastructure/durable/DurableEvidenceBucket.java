package com.parkio.auth.infrastructure.durable;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Provider-neutral I/O for a versioned, retention-locked evidence bucket. Every {@link #put} creates
 * a new immutable version/generation under a retention lock. The <em>oldest</em> non-delete
 * version is canonical: later writes and delete markers cannot replace what was published first.
 *
 * <p>S3 Object Lock version ids and GCS object generations are both addressed as opaque
 * {@code versionId} strings. Callers must not assume S3 listing order equals write order; canonical
 * selection is always {@link #oldest(String)}.
 */
interface DurableEvidenceBucket {

    /** One stored object version/generation. */
    record StoredVersion(String key, String versionId, byte[] bytes) {
    }

    /** Retention lock reported for a stored version. */
    record RetentionLock(EvidenceRetentionMode mode, Instant retainUntil) {
    }

    String bucket();

    /** Fails unless the bucket has the provider's retention-lock feature enabled. */
    void requireRetentionFeature();

    /** Keys under {@code prefix} with at least one non-delete version, ascending. */
    List<String> keys(String prefix);

    /** The first version written under {@code key}, ignoring delete markers. */
    Optional<StoredVersion> oldest(String key);

    /**
     * Every non-delete version of {@code key}. Listing order is provider-defined and must not be
     * treated as write order; use {@link #oldest(String)} for the canonical body.
     */
    List<StoredVersion> allVersions(String key);

    int versionCount(String key);

    /** Writes a new locked version and returns its opaque version/generation id. */
    String put(String key, byte[] bytes, EvidenceRetentionMode mode, ZonedDateTime retainUntil);

    /** Retention lock on an existing version; missing lock is a contract failure for evidence. */
    RetentionLock retention(String key, String versionId);
}
