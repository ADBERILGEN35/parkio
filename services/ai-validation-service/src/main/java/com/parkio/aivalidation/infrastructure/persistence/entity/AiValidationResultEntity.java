package com.parkio.aivalidation.infrastructure.persistence.entity;

import com.parkio.aivalidation.domain.AiValidationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code ai_validation_results}. */
@Entity
@Table(name = "ai_validation_results")
public class AiValidationResultEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "media_id", nullable = false, updatable = false)
    private UUID mediaId;

    @Column(name = "parking_spot_id", updatable = false)
    private UUID parkingSpotId;

    @Column(name = "requested_by_user_id", updatable = false)
    private UUID requestedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AiValidationStatus status;

    @Column(name = "empty_space_confidence", nullable = false)
    private int emptySpaceConfidence;

    @Column(name = "legal_risk_score", nullable = false)
    private int legalRiskScore;

    @Column(name = "image_quality_score", nullable = false)
    private int imageQualityScore;

    @Column(name = "ai_confidence", nullable = false)
    private int aiConfidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected AiValidationResultEntity() {
        // for JPA
    }

    public AiValidationResultEntity(UUID id, UUID mediaId, UUID parkingSpotId, UUID requestedByUserId,
                                    AiValidationStatus status, int emptySpaceConfidence, int legalRiskScore,
                                    int imageQualityScore, int aiConfidence, Instant createdAt, Long version) {
        this.id = id;
        this.mediaId = mediaId;
        this.parkingSpotId = parkingSpotId;
        this.requestedByUserId = requestedByUserId;
        this.status = status;
        this.emptySpaceConfidence = emptySpaceConfidence;
        this.legalRiskScore = legalRiskScore;
        this.imageQualityScore = imageQualityScore;
        this.aiConfidence = aiConfidence;
        this.createdAt = createdAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMediaId() {
        return mediaId;
    }

    public UUID getParkingSpotId() {
        return parkingSpotId;
    }

    public UUID getRequestedByUserId() {
        return requestedByUserId;
    }

    public AiValidationStatus getStatus() {
        return status;
    }

    public int getEmptySpaceConfidence() {
        return emptySpaceConfidence;
    }

    public int getLegalRiskScore() {
        return legalRiskScore;
    }

    public int getImageQualityScore() {
        return imageQualityScore;
    }

    public int getAiConfidence() {
        return aiConfidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}
