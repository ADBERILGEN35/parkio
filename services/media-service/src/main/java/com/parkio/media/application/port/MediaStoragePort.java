package com.parkio.media.application.port;

import java.time.Duration;

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
     * Generates a short-lived presigned GET-only URL for the object. The URL is
     * never persisted — it is created per authorized request and expires after
     * {@code ttl}. Bucket/endpoint details stay inside the adapter.
     */
    String generatePresignedGetUrl(String objectKey, Duration ttl);

    /** Location of a stored object (bucket + key only; access URLs are generated on demand). */
    record StoredObject(String bucket, String objectKey) {
    }
}
