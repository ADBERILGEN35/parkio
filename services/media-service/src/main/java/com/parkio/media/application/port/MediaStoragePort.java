package com.parkio.media.application.port;

import java.time.Duration;
import java.util.List;

/**
 * Port for the object store (S3/MinIO). The adapter owns the bucket and endpoint
 * configuration; callers pass only a generated object key and content. Keeps the
 * application free of any storage-SDK dependency.
 */
public interface MediaStoragePort {

    /** Stores the content under the given key and returns where it landed. */
    StoredObject store(String objectKey, byte[] content, String contentType);

    /** Best-effort removal of a stored object. */
    void delete(String objectKey);

    /**
     * Loads the full content of a stored object. Media objects are small (upload cap
     * is a few MB), so a byte array keeps callers simple; storage failures surface as
     * the adapter's runtime exception.
     */
    byte[] load(String objectKey);

    /**
     * Generates a short-lived presigned GET-only URL for the object. The URL is
     * never persisted — it is created per authorized request and expires after
     * {@code ttl}. Bucket/endpoint details stay inside the adapter.
     */
    String generatePresignedGetUrl(String objectKey, Duration ttl);

    /**
     * Every stored version and delete marker of exactly {@code objectKey} in {@code bucket}; an
     * unversioned bucket reports its one object. Keys that merely share the prefix are not
     * included. Empty means the key is confirmed absent. Account erasure only: the adapter fails
     * for any bucket other than its configured one, where it cannot confirm absence.
     */
    List<StoredVersion> versionsOf(String bucket, String objectKey);

    /** Every stored version and delete marker under {@code prefix} in the configured bucket. */
    List<StoredVersion> versionsUnder(String prefix);

    /**
     * Permanently removes one stored version or delete marker (account erasure). A store that
     * refuses, for example under an object-lock retention, fails the call.
     */
    void removeVersion(StoredVersion version);

    /** Location of a stored object (bucket + key only; access URLs are generated on demand). */
    record StoredObject(String bucket, String objectKey) {
    }

    /**
     * One version of a stored object, or a delete marker. {@code versionId} is the store's id
     * ({@code "null"} for an object written while the bucket was unversioned or suspended).
     */
    record StoredVersion(String bucket, String objectKey, String versionId, boolean deleteMarker) {
    }
}
