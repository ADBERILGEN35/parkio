package com.parkio.auth.infrastructure.durable;

import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import io.minio.GetObjectArgs;
import io.minio.GetObjectLockConfigurationArgs;
import io.minio.GetObjectResponse;
import io.minio.GetObjectRetentionArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S3/MinIO Object Lock implementation of {@link DurableEvidenceBucket}. Every write creates a new
 * version under a retention lock; versions are never overwritten or deleted by this class. The
 * <em>oldest</em> version of a key is its canonical content.
 *
 * <p>Conditional writes send S3's {@code If-None-Match: *} ({@link #putIfAbsent}) or
 * {@code If-Match} with the live version's ETag ({@link #putIfCurrent}); a precondition that no
 * longer holds is answered {@code 412 PreconditionFailed}, writes nothing and returns empty.
 */
final class ObjectLockBucket implements DurableEvidenceBucket {

    private final MinioClient client;
    private final String bucket;
    private final Map<String, StoredVersion> versionCache = new ConcurrentHashMap<>();

    ObjectLockBucket(MinioClient client, String bucket) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public void requireRetentionFeature() {
        requireObjectLock();
    }

    /** Fails unless the bucket exists with object lock (and therefore versioning) enabled. */
    void requireObjectLock() {
        call("object lock configuration", () -> client.getObjectLockConfiguration(
                GetObjectLockConfigurationArgs.builder().bucket(bucket).build()));
    }

    @Override
    public List<String> keys(String prefix) {
        TreeSet<String> keys = new TreeSet<>();
        for (Item item : versions(prefix)) {
            if (!item.isDeleteMarker()) {
                keys.add(item.objectName());
            }
        }
        return List.copyOf(keys);
    }

    @Override
    public Optional<StoredVersion> oldest(String key) {
        List<Item> versions = objectVersions(key);
        return versions.isEmpty() ? Optional.empty() : Optional.of(cached(versions.get(versions.size() - 1)));
    }

    @Override
    public List<StoredVersion> allVersions(String key) {
        List<StoredVersion> all = new ArrayList<>();
        for (Item item : objectVersions(key)) {
            all.add(cached(item));
        }
        return all;
    }

    @Override
    public int versionCount(String key) {
        return objectVersions(key).size();
    }

    @Override
    public String put(String key, byte[] bytes, EvidenceRetentionMode mode, ZonedDateTime retainUntil) {
        RetentionMode minioMode = RetentionMode.valueOf(mode.name());
        return call("put " + key, () -> client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                        .contentType("application/json")
                        .headers(Map.of("Content-MD5", contentMd5(bytes)))
                        .retention(new Retention(minioMode, retainUntil))
                        .build()))
                .versionId();
    }

    @Override
    public Optional<Current> current(String key) {
        return call("get live " + key, () -> {
            try (GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(key).build())) {
                String versionId = response.headers().get("x-amz-version-id");
                String etag = unquote(response.headers().get("ETag"));
                if (versionId == null || etag == null) {
                    throw new IllegalStateException("live version without version id or ETag");
                }
                StoredVersion version = new StoredVersion(key, versionId, response.readAllBytes());
                versionCache.putIfAbsent(key + "\n" + versionId, version);
                return Optional.of(new Current(version, etag));
            } catch (ErrorResponseException ex) {
                // No version, or a delete marker as the live version.
                if ("NoSuchKey".equals(ex.errorResponse().code())) {
                    return Optional.<Current>empty();
                }
                throw ex;
            }
        });
    }

    @Override
    public Optional<Written> putIfAbsent(String key, byte[] bytes, EvidenceRetentionMode mode,
                                         ZonedDateTime retainUntil) {
        return conditionalPut(key, bytes, mode, retainUntil, "If-None-Match", "*");
    }

    @Override
    public Optional<Written> putIfCurrent(String key, byte[] bytes, String matchToken, EvidenceRetentionMode mode,
                                          ZonedDateTime retainUntil) {
        return conditionalPut(key, bytes, mode, retainUntil, "If-Match", "\"" + unquote(matchToken) + "\"");
    }

    /** A version this process wrote is cached, so reading it back costs no request. */
    private Optional<Written> conditionalPut(String key, byte[] bytes, EvidenceRetentionMode mode,
                                             ZonedDateTime retainUntil, String condition, String value) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-MD5", contentMd5(bytes));
        headers.put(condition, value);
        RetentionMode minioMode = RetentionMode.valueOf(mode.name());
        return call("put " + key, () -> {
            ObjectWriteResponse response;
            try {
                response = client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                        .contentType("application/json")
                        .headers(headers)
                        .retention(new Retention(minioMode, retainUntil))
                        .build());
            } catch (ErrorResponseException ex) {
                if ("PreconditionFailed".equals(ex.errorResponse().code())) {
                    return Optional.<Written>empty();
                }
                throw ex;
            }
            if (response.versionId() == null || response.etag() == null) {
                throw new IllegalStateException("write without version id or ETag");
            }
            versionCache.putIfAbsent(key + "\n" + response.versionId(),
                    new StoredVersion(key, response.versionId(), bytes.clone()));
            return Optional.of(new Written(response.versionId(), unquote(response.etag())));
        });
    }

    private static String unquote(String etag) {
        return etag == null ? null : etag.replace("\"", "");
    }

    @Override
    public RetentionLock retention(String key, String versionId) {
        Retention retention = call("retention " + key, () -> client.getObjectRetention(GetObjectRetentionArgs.builder()
                .bucket(bucket).object(key).versionId(versionId).build()));
        if (retention == null || retention.mode() == null || retention.retainUntilDate() == null) {
            throw new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                    "durable store retention " + key + " failed: missing lock");
        }
        return new RetentionLock(EvidenceRetentionMode.valueOf(retention.mode().name()),
                retention.retainUntilDate().toInstant());
    }

    /** Non-delete-marker versions of exactly {@code key}, newest first (S3 listing order). */
    private List<Item> objectVersions(String key) {
        List<Item> versions = new ArrayList<>();
        for (Item item : versions(key)) {
            if (item.objectName().equals(key) && !item.isDeleteMarker()) {
                versions.add(item);
            }
        }
        return versions;
    }

    private List<Item> versions(String prefix) {
        return call("list " + prefix, () -> {
            List<Item> items = new ArrayList<>();
            for (Result<Item> result : client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).includeVersions(true).build())) {
                items.add(result.get());
            }
            return items;
        });
    }

    /** Versions never change, so each is read at most once. */
    private StoredVersion cached(Item item) {
        return versionCache.computeIfAbsent(item.objectName() + "\n" + item.versionId(), ignored -> read(item));
    }

    private StoredVersion read(Item item) {
        return call("get " + item.objectName(), () -> {
            try (GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(item.objectName()).versionId(item.versionId()).build())) {
                return new StoredVersion(item.objectName(), item.versionId(), response.readAllBytes());
            }
        });
    }

    private static String contentMd5(byte[] bytes) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 is required for object-lock writes", ex);
        }
    }

    @FunctionalInterface
    private interface StoreCall<T> {
        T run() throws Exception;
    }

    private static <T> T call(String operation, StoreCall<T> call) {
        try {
            return call.run();
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                    "durable store " + operation + " failed: " + ex.getClass().getSimpleName());
        }
    }
}
