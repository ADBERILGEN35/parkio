package com.parkio.media.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for an uploaded media file. Owns upload metadata, the storage
 * object key, checksum (for duplicate detection) and lifecycle status. References
 * the uploading user only by {@code ownerUserId} — no cross-service link
 * (ai-context/03). Pure domain: no framework, JPA, storage or HTTP dependencies.
 */
public final class MediaFile {

    private final UUID id;
    private final UUID ownerUserId;
    private final String bucketName;
    private final String objectKey;
    private final String accessUrl;
    private final String contentType;
    private final long fileSize;
    private final String checksum;
    private final String perceptualHash;
    private MediaStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
    private final Long version;

    public MediaFile(UUID id,
                     UUID ownerUserId,
                     String bucketName,
                     String objectKey,
                     String accessUrl,
                     String contentType,
                     long fileSize,
                     String checksum,
                     String perceptualHash,
                     MediaStatus status,
                     Instant createdAt,
                     Instant updatedAt,
                     Instant deletedAt,
                     Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName");
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        this.accessUrl = accessUrl;
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        if (fileSize <= 0) {
            throw new IllegalArgumentException("fileSize must be positive");
        }
        this.fileSize = fileSize;
        this.checksum = Objects.requireNonNull(checksum, "checksum");
        this.perceptualHash = perceptualHash;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.deletedAt = deletedAt;
        this.version = version;
    }

    /** Creates a freshly-stored media file in {@link MediaStatus#UPLOADED}. */
    public static MediaFile create(UUID ownerUserId,
                                   String bucketName,
                                   String objectKey,
                                   String accessUrl,
                                   String contentType,
                                   long fileSize,
                                   String checksum,
                                   String perceptualHash,
                                   Instant now) {
        return new MediaFile(UUID.randomUUID(), ownerUserId, bucketName, objectKey, accessUrl,
                contentType, fileSize, checksum, perceptualHash, MediaStatus.UPLOADED, now, now, null, null);
    }

    /** Marks the file as having passed synchronous validation. */
    public void markValidated(Instant now) {
        if (status != MediaStatus.UPLOADED) {
            throw new IllegalStateException("Only UPLOADED media can be validated, was " + status);
        }
        this.status = MediaStatus.VALIDATED;
        this.updatedAt = now;
    }

    /** Soft-deletes the file: hidden from serving, metadata retained for audit. */
    public void softDelete(Instant now) {
        if (status == MediaStatus.DELETED) {
            return;
        }
        this.status = MediaStatus.DELETED;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public boolean isDeleted() {
        return status == MediaStatus.DELETED;
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerUserId.equals(userId);
    }

    public UUID id() {
        return id;
    }

    public UUID ownerUserId() {
        return ownerUserId;
    }

    public String bucketName() {
        return bucketName;
    }

    public String objectKey() {
        return objectKey;
    }

    public String accessUrl() {
        return accessUrl;
    }

    public String contentType() {
        return contentType;
    }

    public long fileSize() {
        return fileSize;
    }

    public String checksum() {
        return checksum;
    }

    public String perceptualHash() {
        return perceptualHash;
    }

    public MediaStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant deletedAt() {
        return deletedAt;
    }

    public Long version() {
        return version;
    }
}
