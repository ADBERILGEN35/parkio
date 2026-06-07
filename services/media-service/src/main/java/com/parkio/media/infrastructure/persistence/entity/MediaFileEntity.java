package com.parkio.media.infrastructure.persistence.entity;

import com.parkio.media.domain.MediaStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code media_files}. A persistence detail, not the domain. */
@Entity
@Table(name = "media_files")
public class MediaFileEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "bucket_name", nullable = false, updatable = false)
    private String bucketName;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Column(name = "access_url")
    private String accessUrl;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "file_size", nullable = false, updatable = false)
    private long fileSize;

    @Column(name = "checksum", nullable = false, updatable = false)
    private String checksum;

    @Column(name = "perceptual_hash")
    private String perceptualHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MediaStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected MediaFileEntity() {
        // for JPA
    }

    public MediaFileEntity(UUID id,
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
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.bucketName = bucketName;
        this.objectKey = objectKey;
        this.accessUrl = accessUrl;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.checksum = checksum;
        this.perceptualHash = perceptualHash;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public String getBucketName() {
        return bucketName;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getAccessUrl() {
        return accessUrl;
    }

    public String getContentType() {
        return contentType;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getPerceptualHash() {
        return perceptualHash;
    }

    public MediaStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Long getVersion() {
        return version;
    }
}
