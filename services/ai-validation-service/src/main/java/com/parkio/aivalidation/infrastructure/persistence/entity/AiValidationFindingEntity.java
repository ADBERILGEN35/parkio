package com.parkio.aivalidation.infrastructure.persistence.entity;

import com.parkio.aivalidation.domain.AiRiskType;
import com.parkio.aivalidation.domain.AiValidationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code ai_validation_findings} (child of a result). */
@Entity
@Table(name = "ai_validation_findings")
public class AiValidationFindingEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "validation_result_id", nullable = false, updatable = false)
    private UUID validationResultId;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_type", nullable = false, updatable = false)
    private AiValidationType validationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_type", updatable = false)
    private AiRiskType riskType;

    @Column(name = "score", nullable = false, updatable = false)
    private int score;

    @Column(name = "message", updatable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiValidationFindingEntity() {
        // for JPA
    }

    public AiValidationFindingEntity(UUID id, UUID validationResultId, AiValidationType validationType,
                                     AiRiskType riskType, int score, String message, Instant createdAt) {
        this.id = id;
        this.validationResultId = validationResultId;
        this.validationType = validationType;
        this.riskType = riskType;
        this.score = score;
        this.message = message;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getValidationResultId() {
        return validationResultId;
    }

    public AiValidationType getValidationType() {
        return validationType;
    }

    public AiRiskType getRiskType() {
        return riskType;
    }

    public int getScore() {
        return score;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
