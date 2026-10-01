package com.parkio.media.infrastructure.storage;

import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.infrastructure.config.MediaProperties;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    @Override
    public StoredObject store(String objectKey, byte[] content, String contentType) {
        try {
            internalClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new MediaStorageException("Failed to store media object", e);
        }
        return new StoredObject(bucket, objectKey);
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
            internalClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
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
            if (versions.isEmpty() && currentObjectExists(objectKey)) {
                versions.add(new StoredVersion(bucket, objectKey, null, false));
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

    private boolean currentObjectExists(String objectKey) throws Exception {
        try {
            internalClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
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
