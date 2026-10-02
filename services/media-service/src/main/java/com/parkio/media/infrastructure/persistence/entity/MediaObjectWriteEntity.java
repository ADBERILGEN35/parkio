package com.parkio.media.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Schema mapping of {@code media_object_writes} (V16); rows are read and written through
 * {@code MediaObjectWriteLedger} and {@code MediaErasureJobStore} with plain SQL.
 */
@Entity
@Table(name = "media_object_writes")
public class MediaObjectWriteEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "bucket_name", nullable = false, length = 128)
    private String bucketName;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MediaObjectWriteEntity() {
    }
}
