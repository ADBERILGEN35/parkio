package com.parkio.auth.infrastructure.durable;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.StorageException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Production {@link GcsObjectRetentionTransport} over {@link Storage}. Enabled only through
 * {@link GcsDurableStoreConfig}. Does not provision buckets or enable Object Retention Lock.
 */
final class GoogleCloudStorageTransport implements GcsObjectRetentionTransport {

    private final Storage storage;
    private final String bucket;

    GoogleCloudStorageTransport(Storage storage, String bucket) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public void requireObjectRetentionEnabled() {
        Bucket existing = storage.get(bucket, Storage.BucketGetOption.fields(
                Storage.BucketField.ID, Storage.BucketField.OBJECT_RETENTION));
        if (existing == null) {
            throw new StorageException(404, "bucket not found: " + bucket);
        }
        BucketInfo.ObjectRetention objectRetention = existing.getObjectRetention();
        if (objectRetention == null || objectRetention.getMode() != BucketInfo.ObjectRetention.Mode.ENABLED) {
            throw new IllegalStateException("bucket " + bucket + " does not have Object Retention Lock enabled");
        }
    }

    @Override
    public long createGeneration(String objectName, byte[] bytes, EvidenceRetentionMode mode, Instant retainUntil) {
        BlobInfo.Retention.Mode gcsMode = mode == EvidenceRetentionMode.COMPLIANCE
                ? BlobInfo.Retention.Mode.LOCKED
                : BlobInfo.Retention.Mode.UNLOCKED;
        BlobInfo.Retention retention = BlobInfo.Retention.newBuilder()
                .setMode(gcsMode)
                .setRetainUntilTime(OffsetDateTime.ofInstant(retainUntil, ZoneOffset.UTC))
                .build();
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
                .setContentType("application/json")
                .setRetention(retention)
                .build();
        Blob created = storage.create(info, bytes);
        return Objects.requireNonNull(created.getGeneration(), "generation");
    }

    @Override
    public RetentionState retention(String objectName, long generation) {
        Blob blob = storage.get(BlobId.of(bucket, objectName, generation));
        if (blob == null || blob.getRetention() == null || blob.getRetention().getMode() == null
                || blob.getRetention().getRetainUntilTime() == null) {
            throw new IllegalStateException("missing object retention on " + objectName + "#" + generation);
        }
        EvidenceRetentionMode mode = blob.getRetention().getMode() == BlobInfo.Retention.Mode.LOCKED
                ? EvidenceRetentionMode.COMPLIANCE
                : EvidenceRetentionMode.GOVERNANCE;
        return new RetentionState(mode, blob.getRetention().getRetainUntilTime().toInstant());
    }

    @Override
    public ListPage listGenerations(String prefix, Optional<String> pageToken, int pageSize) {
        List<BlobListOption> options = new ArrayList<>();
        options.add(BlobListOption.prefix(prefix));
        options.add(BlobListOption.versions(true));
        options.add(BlobListOption.pageSize(pageSize));
        pageToken.ifPresent(token -> options.add(BlobListOption.pageToken(token)));
        Page<Blob> page = storage.list(bucket, options.toArray(BlobListOption[]::new));
        List<ListedGeneration> items = new ArrayList<>();
        for (Blob blob : page.getValues()) {
            // GCS object versioning does not use S3-style delete markers; generations are listed
            // directly. Soft-delete (if enabled) is out of scope for this transport.
            if (blob.getGeneration() == null) {
                continue;
            }
            items.add(new ListedGeneration(blob.getName(), blob.getGeneration(), false));
        }
        String next = page.hasNextPage() ? page.getNextPageToken() : null;
        return new ListPage(List.copyOf(items), Optional.ofNullable(next).filter(s -> !s.isBlank()));
    }

    @Override
    public byte[] readGeneration(String objectName, long generation) {
        byte[] bytes = storage.readAllBytes(BlobId.of(bucket, objectName, generation));
        return bytes;
    }
}
