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

    /**
     * A version a conditional write created: its id, and the token a later
     * {@link #putIfCurrent} must name to replace it (GCS: the generation; S3: the ETag).
     */
    record Written(String versionId, String matchToken) {
    }

    /** The live (current) version of a key and the token a conditional replace must name. */
    record Current(StoredVersion version, String matchToken) {
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

    /**
     * The live version of {@code key}, as a read without a version id returns it; empty when the
     * key has none. This is the version {@link #putIfCurrent} compares against, not necessarily the
     * canonical {@link #oldest(String)}.
     */
    Optional<Current> current(String key);

    /**
     * Writes a locked version only while {@code key} has no live version (GCS
     * {@code ifGenerationMatch=0}, S3 {@code If-None-Match: *}); empty when it has one, and nothing
     * is written. A key whose live version was deleted can still hold older versions: the canonical
     * version stays {@link #oldest(String)}.
     */
    Optional<Written> putIfAbsent(String key, byte[] bytes, EvidenceRetentionMode mode, ZonedDateTime retainUntil);

    /**
     * Writes a locked version only while the live version of {@code key} is still the one
     * {@code matchToken} names (GCS {@code ifGenerationMatch}, S3 {@code If-Match}); empty when it
     * changed, and nothing is written.
     */
    Optional<Written> putIfCurrent(String key, byte[] bytes, String matchToken, EvidenceRetentionMode mode,
                                   ZonedDateTime retainUntil);

    /** Retention lock on an existing version; missing lock is a contract failure for evidence. */
    RetentionLock retention(String key, String versionId);

    /**
     * The store's estimate, after each frontier read or write, of the object versions its longest
     * listings hold: the frontier's versions and the sequence markers, about the frontier's
     * {@code highestReserved + 1}. The normal path lists none of them, so an adapter that bounds its
     * listings uses this to warn before they outgrow the bounds (#616). The default ignores it.
     */
    default void historyEstimate(long versions) {
    }
}
