package com.parkio.auth.infrastructure.durable;

import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import io.minio.GetObjectArgs;
import io.minio.GetObjectLockConfigurationArgs;
import io.minio.GetObjectResponse;
import io.minio.GetObjectRetentionArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versioned object-lock bucket I/O for durable erasure evidence. Every write creates a new
 * version under a retention lock; versions are never overwritten or deleted by this class.
 * The <em>oldest</em> version of a key is its canonical content, so a later write or a
 * delete marker cannot replace what was published first.
 */
final class ObjectLockBucket {

    /** One stored object version. */
    record StoredVersion(String key, String versionId, byte[] bytes) {
    }

    private final MinioClient client;
    private final String bucket;
    private final Map<String, StoredVersion> versionCache = new ConcurrentHashMap<>();

    ObjectLockBucket(MinioClient client, String bucket) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    String bucket() {
        return bucket;
    }

    /** Fails unless the bucket exists with object lock (and therefore versioning) enabled. */
    void requireObjectLock() {
        call("object lock configuration", () -> client.getObjectLockConfiguration(
                GetObjectLockConfigurationArgs.builder().bucket(bucket).build()));
    }

    /** Keys under {@code prefix} that have at least one non-delete-marker version, ascending. */
    List<String> keys(String prefix) {
        TreeSet<String> keys = new TreeSet<>();
        for (Item item : versions(prefix)) {
            if (!item.isDeleteMarker()) {
                keys.add(item.objectName());
            }
        }
        return List.copyOf(keys);
    }

    /** The first version written under {@code key}, ignoring delete markers. */
    Optional<StoredVersion> oldest(String key) {
        List<Item> versions = objectVersions(key);
        return versions.isEmpty() ? Optional.empty() : Optional.of(read(versions.get(versions.size() - 1)));
    }

    /**
     * Every version of {@code key} except delete markers, in listing order (newest modification
     * time first, which a backward clock step on the store host can disturb). A version's bytes
     * never change, so each version is read once per bucket instance.
     */
    List<StoredVersion> allVersions(String key) {
        List<StoredVersion> all = new ArrayList<>();
        for (Item item : objectVersions(key)) {
            all.add(versionCache.computeIfAbsent(key + "\n" + item.versionId(), ignored -> read(item)));
        }
        return all;
    }

    /** The most recent version written under {@code key}, ignoring delete markers. */
    Optional<StoredVersion> latest(String key) {
        List<Item> versions = objectVersions(key);
        return versions.isEmpty() ? Optional.empty() : Optional.of(read(versions.get(0)));
    }

    int versionCount(String key) {
        return objectVersions(key).size();
    }

    /** Writes a new locked version and returns its version id. */
    String put(String key, byte[] bytes, RetentionMode mode, ZonedDateTime retainUntil) {
        return call("put " + key, () -> client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                        .contentType("application/json")
                        .headers(Map.of("Content-MD5", contentMd5(bytes)))
                        .retention(new Retention(mode, retainUntil))
                        .build()))
                .versionId();
    }

    Retention retention(String key, String versionId) {
        return call("retention " + key, () -> client.getObjectRetention(GetObjectRetentionArgs.builder()
                .bucket(bucket).object(key).versionId(versionId).build()));
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
            // Object-lock writes need Content-MD5 (S3 rejects retention headers without it).
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
