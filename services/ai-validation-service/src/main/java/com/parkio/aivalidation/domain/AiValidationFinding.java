package com.parkio.aivalidation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A single advisory finding within a result (the outcome of one check). {@code riskType}
 * is present only for legal-risk findings. {@code score} is 0-100. Pure domain.
 */
public final class AiValidationFinding {

    private final UUID id;
    private final AiValidationType validationType;
    private final AiRiskType riskType;
    private final int score;
    private final String message;
    private final Instant createdAt;

    public AiValidationFinding(UUID id, AiValidationType validationType, AiRiskType riskType,
                               int score, String message, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.validationType = Objects.requireNonNull(validationType, "validationType");
        this.riskType = riskType;
        this.score = Score.require("score", score);
        this.message = message;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static AiValidationFinding of(AiValidationType validationType, AiRiskType riskType,
                                         int score, String message, Instant now) {
        return new AiValidationFinding(UUID.randomUUID(), validationType, riskType, score, message, now);
    }

    public UUID id() {
        return id;
    }

    public AiValidationType validationType() {
        return validationType;
    }

    public Optional<AiRiskType> riskType() {
        return Optional.ofNullable(riskType);
    }

    public int score() {
        return score;
    }

    public String message() {
        return message;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
