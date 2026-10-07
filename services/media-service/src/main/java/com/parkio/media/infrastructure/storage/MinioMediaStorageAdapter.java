package com.parkio.media.infrastructure.storage;

import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.infrastructure.config.MediaProperties;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ObjectWriteArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Item;
import io.minio.messages.ListVersionsResult;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import okhttp3.Response;
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
    private final VersionListingClient listing;
    private final String bucket;
    /** Isolated recovery only: the restored rows' bucket, listed here in the configured bucket. */
    private final Optional<String> restoredSourceBucket;
    private final int listingPageSize;

    public MinioMediaStorageAdapter(
            @Qualifier("internalMinioClient") MinioClient internalClient,
            @Qualifier("presignMinioClient") MinioClient presignClient,
            VersionListingClient listing,
            MediaProperties properties,
            @Value("${parkio.media.erasure-worker.listing-page-size:100}") int listingPageSize,
            @Value("${parkio.privacy.restore-replay.enabled:false}") boolean restoreReplayEnabled) {
        if (listingPageSize < 1 || listingPageSize > 1000) {
            throw new IllegalArgumentException("parkio.media.erasure-worker.listing-page-size must be 1..1000");
        }
        this.internalClient = internalClient;
        this.presignClient = presignClient;
        this.listing = listing;
        this.bucket = properties.getStorage().getBucket();
        this.restoredSourceBucket = RestoredSourceBucket.of(
                properties.getStorage().getRestoredSourceBucket(), bucket, restoreReplayEnabled);
        this.listingPageSize = listingPageSize;
    }

    /**
     * One PutObject request, transmitted once: the part size is at least the content length, so the
     * SDK never splits the upload into a multipart upload (whose parts would be stored data outside
     * any listing of objects), and the storage client's {@link SingleTransmissionInterceptor} never
     * sends the body a second time (no follow-up after a reply, no retry after sending).
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
     * Whether the write certainly was not applied, judged from evidence about every transmission of
     * the call rather than from its last reply: no attempt started to send the body (the single-
     * transmission guard's {@code BodyNotSentException}), or the store answered the only
     * transmission of the guarded body with a client error (4xx) and no follow-up request was made.
     * Anything else may have been applied: a timeout, a broken connection, a 5xx or 3xx reply, a
     * failed connection after an earlier attempt, a reply without the guard's evidence.
     */
    static boolean definitelyNotApplied(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SingleTransmissionInterceptor.BodyNotSentException) {
                return true;
            }
        }
        if (failure instanceof ErrorResponseException rejected && rejected.response() != null) {
            Response reply = rejected.response();
            return reply.code() >= 400 && reply.code() < 500 && reply.priorResponse() == null
                    && SingleTransmissionInterceptor.sentAtMostOnce(reply.request());
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
     * Versions and delete markers of exactly the key, so a versioned bucket's older versions are
     * never mistaken for absence, from one ListObjectVersions request with the key as prefix. Keys
     * are listed in order and a key sorts before every longer key it prefixes, so the key's own
     * entries come first: the page holds all of them unless they fill it (then the rest follow once
     * these are removed), and keys that only share the prefix are never paged through. An empty
     * listing is backed by a HEAD: an object the listing missed is still reported, never confirmed
     * absent. During an isolated recovery a key named in the restored source bucket is listed in
     * the configured bucket, where the restore put it ({@link RestoredSourceBucket}); the versions
     * found carry the configured bucket.
     */
    @Override
    public List<StoredVersion> versionsOf(String objectBucket, String objectKey) {
        requireErasableBucket(objectBucket);
        try {
            List<StoredVersion> versions = page(objectKey, objectKey::equals);
            if (versions.isEmpty()) {
                currentVersionId(objectKey).ifPresent(versionId ->
                        versions.add(new StoredVersion(bucket, objectKey, versionId, false)));
            }
            return versions;
        } catch (Exception e) {
            throw new MediaStorageException("Failed to list media object versions", e);
        }
    }

    /** One ListObjectVersions request: the first {@code listing-page-size} entries under the prefix. */
    @Override
    public List<StoredVersion> versionsUnder(String prefix) {
        try {
            return page(prefix, key -> key.startsWith(prefix));
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

    /** The matching entries of one listing page: exactly one request, whatever the page holds. */
    private List<StoredVersion> page(String prefix, Predicate<String> matches) throws Exception {
        ListVersionsResult page = listing.firstPage(bucket, prefix, listingPageSize);
        List<StoredVersion> versions = new ArrayList<>();
        for (Item item : page.contents()) {
            if (matches.test(item.objectName())) {
                versions.add(new StoredVersion(bucket, item.objectName(), item.versionId(), item.isDeleteMarker()));
            }
        }
        for (Item marker : page.deleteMarkers()) {
            if (matches.test(marker.objectName())) {
                versions.add(new StoredVersion(bucket, marker.objectName(), marker.versionId(), true));
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
     * Account erasure's bucket rule: the configured bucket, or during an isolated recovery the
     * restored source bucket, whose objects the restore put into the configured bucket. Any other
     * bucket is refused as before.
     */
    private void requireErasableBucket(String objectBucket) {
        if (restoredSourceBucket.isPresent() && restoredSourceBucket.get().equals(objectBucket)) {
            return;
        }
        requireConfiguredBucket(objectBucket);
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
