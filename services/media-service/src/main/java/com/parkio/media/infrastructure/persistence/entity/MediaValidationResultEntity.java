package com.parkio.media.infrastructure.persistence.entity;

import com.parkio.media.domain.MediaValidationOutcome;
import com.parkio.media.domain.MediaValidationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code media_validation_results} (append-only). */
@Entity
@Table(name = "media_validation_results")
public class MediaValidationResultEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "media_id", nullable = false, updatable = false)
    private UUID mediaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_type", nullable = false, updatable = false)
    private MediaValidationType validationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, updatable = false)
    private MediaValidationOutcome result;

    @Column(name = "message", updatable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MediaValidationResultEntity() {
        // for JPA
    }

    public MediaValidationResultEntity(UUID id,
                                       UUID mediaId,
                                       MediaValidationType validationType,
                                       MediaValidationOutcome result,
                                       String message,
                                       Instant createdAt) {
        this.id = id;
        this.mediaId = mediaId;
        this.validationType = validationType;
        this.result = result;
        this.message = message;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMediaId() {
        return mediaId;
    }

    public MediaValidationType getValidationType() {
        return validationType;
    }

    public MediaValidationOutcome getResult() {
        return result;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
