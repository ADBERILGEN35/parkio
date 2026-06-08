package com.parkio.aivalidation.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate root for an advisory AI validation. Holds the headline scores plus its
 * child {@link AiValidationFinding}s and {@link VehicleFitEstimate}s. References media
 * and (optionally) a parking spot by id only — ai-validation-service never mutates
 * those services' data (ai-context/03) and its result is advisory, not a decision
 * (ai-context/02). Pure domain: no framework dependencies.
 */
public final class AiValidationResult {

    private final UUID id;
    private final UUID mediaId;
    private final UUID parkingSpotId;
    private final UUID requestedByUserId;
    private final AiValidationStatus status;
    private final int emptySpaceConfidence;
    private final int legalRiskScore;
    private final int imageQualityScore;
    private final int aiConfidence;
    private final List<AiValidationFinding> findings;
    private final List<VehicleFitEstimate> vehicleFitEstimates;
    private final Instant createdAt;
    private final Long version;

    public AiValidationResult(UUID id, UUID mediaId, UUID parkingSpotId, UUID requestedByUserId,
                              AiValidationStatus status, int emptySpaceConfidence, int legalRiskScore,
                              int imageQualityScore, int aiConfidence, List<AiValidationFinding> findings,
                              List<VehicleFitEstimate> vehicleFitEstimates, Instant createdAt, Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.mediaId = Objects.requireNonNull(mediaId, "mediaId");
        this.parkingSpotId = parkingSpotId;
        this.requestedByUserId = requestedByUserId;
        this.status = Objects.requireNonNull(status, "status");
        this.emptySpaceConfidence = Score.require("emptySpaceConfidence", emptySpaceConfidence);
        this.legalRiskScore = Score.require("legalRiskScore", legalRiskScore);
        this.imageQualityScore = Score.require("imageQualityScore", imageQualityScore);
        this.aiConfidence = Score.require("aiConfidence", aiConfidence);
        this.findings = List.copyOf(findings);
        this.vehicleFitEstimates = List.copyOf(vehicleFitEstimates);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    /**
     * Builds a new result, deriving the advisory status from the scores and detected
     * risks via {@link AiValidationStatusPolicy}. {@code status} is never caller-supplied.
     */
    public static AiValidationResult create(UUID mediaId, UUID parkingSpotId, UUID requestedByUserId,
                                            int emptySpaceConfidence, int legalRiskScore, int imageQualityScore,
                                            int aiConfidence, List<AiValidationFinding> findings,
                                            List<VehicleFitEstimate> vehicleFitEstimates, Instant now) {
        List<AiRiskType> detectedRiskTypes = detectedRiskTypes(findings);
        AiValidationStatus status = AiValidationStatusPolicy.evaluate(
                legalRiskScore, imageQualityScore, aiConfidence, detectedRiskTypes);
        return new AiValidationResult(UUID.randomUUID(), mediaId, parkingSpotId, requestedByUserId, status,
                emptySpaceConfidence, legalRiskScore, imageQualityScore, aiConfidence, findings,
                vehicleFitEstimates, now, null);
    }

    /** The distinct risk types flagged across this result's findings (advisory). */
    public List<AiRiskType> detectedRiskTypes() {
        return detectedRiskTypes(findings);
    }

    private static List<AiRiskType> detectedRiskTypes(List<AiValidationFinding> findings) {
        return findings.stream()
                .map(AiValidationFinding::riskType)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    public UUID id() {
        return id;
    }

    public UUID mediaId() {
        return mediaId;
    }

    public Optional<UUID> parkingSpotId() {
        return Optional.ofNullable(parkingSpotId);
    }

    public Optional<UUID> requestedByUserId() {
        return Optional.ofNullable(requestedByUserId);
    }

    public AiValidationStatus status() {
        return status;
    }

    public int emptySpaceConfidence() {
        return emptySpaceConfidence;
    }

    public int legalRiskScore() {
        return legalRiskScore;
    }

    public int imageQualityScore() {
        return imageQualityScore;
    }

    public int aiConfidence() {
        return aiConfidence;
    }

    public List<AiValidationFinding> findings() {
        return findings;
    }

    public List<VehicleFitEstimate> vehicleFitEstimates() {
        return vehicleFitEstimates;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Long version() {
        return version;
    }
}
