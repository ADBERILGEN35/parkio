package com.parkio.media.infrastructure.storage;

import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.infrastructure.config.MediaProperties;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.ObjectWriteArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stores media bytes in an S3-compatible object store (MinIO) via the configured
 * bucket. Implements {@link MediaStoragePort}; the SDK stays confined to this
 * adapter so the application/domain never see it.
 *
 * <p>Object operations use the internal endpoint; presigned GET URLs are generated
 * with the public endpoint so the signed {@code Host} matches the browser request.
 */
@Component
public class MinioMediaStorageAdapter implements MediaStoragePort {

    /** Bound on listings in one {@link #delete}; a key has one version unless the bucket is versioned. */
    private static final int MAX_DELETE_LISTINGS = 10;

    private final MinioClient internalClient;
    private final MinioClient presignClient;
    private final String bucket;
    private final int listingPageSize;

    public MinioMediaStorageAdapter(
            @Qualifier("internalMinioClient") MinioClient internalClient,
            @Qualifier("presignMinioClient") MinioClient presignClient,
            MediaProperties properties,
            @Value("${parkio.media.erasure-worker.listing-page-size:100}") int listingPageSize) {
        if (listingPageSize < 1 || listingPageSize > 1000) {
            throw new IllegalArgumentException("parkio.media.erasure-worker.listing-page-size must be 1..1000");
        }
        this.internalClient = internalClient;
        this.presignClient = presignClient;
        this.bucket = properties.getStorage().getBucket();
        this.listingPageSize = listingPageSize;
    }

    /**
     * One PutObject request: the part size is at least the content length, so the SDK never splits
     * the upload into a multipart upload (whose parts would be stored data outside any listing of
     * objects), and the HTTP client never retries a request (see {@code MediaInfrastructureConfig}).
     */
    @Override
    public StoredObject store(String objectKey, byte[] content, String contentType) {
        try {
            internalClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length,
                            Math.max(content.length, ObjectWriteArgs.MIN_MULTIPART_SIZE))
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            if (definitelyNotApplied(e)) {
                throw new WriteNotAppliedException("Media object write rejected; nothing was stored", e);
            }
            throw new MediaStorageException("Failed to store media object; the write's outcome is unknown", e);
        }
        return new StoredObject(bucket, objectKey);
    }

    /**
     * A client-error reply (4xx) means the store rejected this request. A failure to open the
     * connection means no byte of it was sent (the client does not retry, so this was the only
     * attempt). Everything else, timeouts and 5xx replies included, may have been applied.
     */
    static boolean definitelyNotApplied(Exception failure) {
        if (failure instanceof ErrorResponseException rejected && rejected.response() != null) {
            int status = rejected.response().code();
            return status >= 400 && status < 500;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public byte[] load(String objectKey) {
        try (var stream = internalClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new MediaStorageException("Failed to load media object", e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Set<StoredVersion> removed = new HashSet<>();
            for (int listing = 0; listing < MAX_DELETE_LISTINGS; listing++) {
                List<StoredVersion> versions = versionsOf(bucket, objectKey);
                if (versions.isEmpty()) {
                    return;
                }
                for (StoredVersion version : versions) {
                    if (!removed.add(version)) {
                        throw new MediaStorageException("Media object version still present after its delete", null);
                    }
                    removeVersion(version);
                }
            }
            throw new MediaStorageException("Media object versions not confirmed removed", null);
        } catch (MediaStorageException e) {
            throw e;
        } catch (Exception e) {
            throw new MediaStorageException("Failed to remove media object", e);
        }
    }

    /**
     * Presigned GET-only URL, signed locally with the configured credentials (no
     * network call). Uses the public endpoint client so the embedded host matches
     * what the browser will request. Expires after {@code ttl}; never persisted by
     * callers.
     */
    @Override
    public String generatePresignedGetUrl(String objectKey, Duration ttl) {
        try {
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new MediaStorageException("Failed to presign media object", e);
        }
    }

    /**
     * Lists versions and delete markers (ListObjectVersions), so a versioned bucket's older
     * versions are never mistaken for absence; at most one page ({@code listing-page-size}
     * entries, one request) per call. An empty listing is backed by a HEAD: an object the listing
     * missed is still reported, never confirmed absent.
     */
    @Override
    public List<StoredVersion> versionsOf(String objectBucket, String objectKey) {
        requireConfiguredBucket(objectBucket);
        try {
            List<StoredVersion> versions = list(objectKey, objectKey::equals);
            if (versions.isEmpty()) {
                currentVersionId(objectKey).ifPresent(versionId ->
                        versions.add(new StoredVersion(bucket, objectKey, versionId, false)));
            }
            return versions;
        } catch (Exception e) {
            throw new MediaStorageException("Failed to list media object versions", e);
        }
    }

    @Override
    public List<StoredVersion> versionsUnder(String prefix) {
        try {
            return list(prefix, key -> key.startsWith(prefix));
        } catch (Exception e) {
            throw new MediaStorageException("Failed to list media object versions", e);
        }
    }

    /** Deletes by version id: a permanent delete, unlike a key-only delete on a versioned bucket. */
    @Override
    public void removeVersion(StoredVersion version) {
        requireConfiguredBucket(version.bucket());
        try {
            internalClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(version.objectKey())
                    .versionId(version.versionId())
                    .build());
        } catch (Exception e) {
            throw new MediaStorageException("Failed to remove media object version", e);
        }
    }

    /** At most one page of matching entries: the iterator fetches the next page lazily, so stopping here bounds the call. */
    private List<StoredVersion> list(String prefix, Predicate<String> matches) throws Exception {
        List<StoredVersion> versions = new ArrayList<>();
        for (Result<Item> result : internalClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(prefix)
                .includeVersions(true)
                .recursive(true)
                .maxKeys(listingPageSize)
                .build())) {
            Item item = result.get();
            if (matches.test(item.objectName())) {
                versions.add(new StoredVersion(bucket, item.objectName(), item.versionId(), item.isDeleteMarker()));
                if (versions.size() == listingPageSize) {
                    break;
                }
            }
        }
        return versions;
    }

    /**
     * The current version's id by HEAD, or empty if the key does not exist. An unversioned object
     * reports {@code "null"}, so removing it is never a key-only delete (which would add a delete
     * marker in a versioned bucket).
     */
    private Optional<String> currentVersionId(String objectKey) throws Exception {
        try {
            String versionId = internalClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectKey).build()).versionId();
            return Optional.of(versionId == null ? "null" : versionId);
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Every media row records its bucket, but this adapter reads and deletes only in its
     * configured one; deleting the key there proves nothing about another bucket.
     */
    private void requireConfiguredBucket(String objectBucket) {
        if (!bucket.equals(objectBucket)) {
            throw new MediaStorageException("Object is stored in bucket '" + objectBucket
                    + "', not the configured bucket '" + bucket + "'; its deletion cannot be confirmed", null);
        }
    }
}
