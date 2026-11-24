package com.parkio.auth.infrastructure.durable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Narrow transport used by {@link GcsObjectRetentionBucket}. Production wiring uses the Google
 * Cloud Storage SDK; tests inject a controlled in-memory stub. Not a public SPI.
 */
interface GcsObjectRetentionTransport {

    /** Code of a write refused because its {@code ifGenerationMatch} precondition did not hold. */
    int PRECONDITION_FAILED = 412;

    record GenerationBytes(long generation, byte[] bytes) {
    }

    record RetentionState(EvidenceRetentionMode mode, Instant retainUntil) {
    }

    record ListPage(List<ListedGeneration> items, Optional<String> nextPageToken) {
    }

    record ListedGeneration(String objectName, long generation, boolean deleteMarker) {
    }

    String bucket();

    /**
     * Fails unless the bucket has Object Retention Lock enabled (not merely Bucket Lock) and Object
     * Versioning, so that every write adds a generation.
     */
    void requireObjectRetentionEnabled();

    /**
     * Creates a new generation with the given retention. Returns the generation number. Must not
     * overwrite prior generations in place. A write the provider refuses under its per-object rate
     * fails with a {@link com.google.cloud.storage.StorageException} of code 429 and writes nothing.
     *
     * <p>With {@code ifGenerationMatch} the write is conditional, as Cloud Storage's precondition:
     * {@code 0} writes only while the object has no live generation, any other value only while the
     * live generation is that one. A precondition that does not hold fails with code
     * {@link #PRECONDITION_FAILED} and writes nothing.
     */
    long createGeneration(String objectName, byte[] bytes, EvidenceRetentionMode mode, Instant retainUntil,
                          OptionalLong ifGenerationMatch);

    /** The live generation of {@code objectName} and its bytes; empty when the object has none. */
    Optional<GenerationBytes> liveGeneration(String objectName);

    RetentionState retention(String objectName, long generation);

    /**
     * Lists object generations under {@code prefix}. {@code pageToken} is opaque; empty starts at
     * the beginning. Page size must be positive.
     */
    ListPage listGenerations(String prefix, Optional<String> pageToken, int pageSize);

    byte[] readGeneration(String objectName, long generation);
}
