package com.parkio.media.application.port;

import java.time.Duration;
import java.util.List;

/**
 * Port for the object store (S3/MinIO). The adapter owns the bucket and endpoint
 * configuration; callers pass only a generated object key and content. Keeps the
 * application free of any storage-SDK dependency.
 */
public interface MediaStoragePort {

    /**
     * Stores the content under the given key, in one request whose body is transmitted at most
     * once, and returns where it landed. Throws {@link WriteNotAppliedException} only when the store
     * certainly did not apply the write; any other failure leaves its outcome unknown (the store may
     * still apply that one transmission later).
     */
    StoredObject store(String objectKey, byte[] content, String contentType);

    /**
     * Removes every stored version of the key, each by its version id, so a delete that reaches the
     * store late can only remove data, never add a delete marker. Throws if the key is not
     * confirmed absent afterwards; callers treat it as best effort.
     */
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
     * Stored versions and delete markers of exactly {@code objectKey} in {@code bucket}, from one
     * listing request (plus a HEAD when the listing is empty); more may follow once these are
     * removed (an unversioned bucket reports its one object). Keys that merely share the prefix are
     * neither included nor paged through. Empty means the key is confirmed absent. Account erasure
     * only: the adapter fails for any bucket other than its configured one, where it cannot confirm
     * absence.
     */
    List<StoredVersion> versionsOf(String bucket, String objectKey);

    /**
     * Stored versions and delete markers under {@code prefix} in the configured bucket, from one
     * listing request (one page; no per-key HEAD). Empty means nothing is stored under the prefix.
     */
    List<StoredVersion> versionsUnder(String prefix);

    /**
     * Permanently removes one stored version or delete marker (account erasure). A store that
     * refuses, for example under an object-lock retention, fails the call.
     */
    void removeVersion(StoredVersion version);

    /**
     * The store certainly did not apply a write: no attempt sent any byte of its body, or the store
     * answered its only transmission with a client error (4xx). Any other failure, including a
     * timeout, a 5xx or 3xx reply, or a failure of a later attempt after the body was sent, leaves
     * the outcome unknown.
     */
    final class WriteNotAppliedException extends RuntimeException {
        public WriteNotAppliedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

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
