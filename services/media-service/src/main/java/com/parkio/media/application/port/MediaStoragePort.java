package com.parkio.media.application.port;

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

    /** Location of a stored object. {@code accessUrl} may be {@code null} (signed URLs are a later concern). */
    record StoredObject(String bucket, String objectKey, String accessUrl) {
    }
}
