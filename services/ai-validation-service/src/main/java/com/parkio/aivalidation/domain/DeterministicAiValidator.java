package com.parkio.aivalidation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Placeholder validator that produces deterministic, advisory scores from simple
 * inputs — <strong>no real AI provider is called</strong>. Scores are derived from the
 * media id so the same input always yields the same safe, plausible result. Real model
 * integration (vision provider behind a port + infrastructure adapter) is backlog.
 *
 * <p>Pure domain: no framework or provider SDK dependencies (ai-context/01).
 */
public final class DeterministicAiValidator {

    /**
     * Runs the placeholder checks and assembles an advisory result. The status is
     * derived by {@link AiValidationResult#create} via {@link AiValidationStatusPolicy}.
     */
    public AiValidationResult validate(UUID mediaId, UUID parkingSpotId, UUID requestedByUserId, Instant now) {
        int seed = seedOf(mediaId);

        int imageQuality = Score.clamp(70 + seed % 30);       // 70-99: acceptable
        int emptySpace = Score.clamp(60 + seed % 40);         // 60-99
        int legalRisk = Score.clamp(seed % 30);               // 0-29: low
        int duplicateRisk = Score.clamp(seed % 20);           // 0-19: low
        int aiConfidence = Score.clamp(65 + seed % 35);       // 65-99

        List<AiValidationFinding> findings = List.of(
                AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                        Score.clamp(70 + seed % 30), "Parking space is visible in the image.", now),
                AiValidationFinding.of(AiValidationType.EMPTY_SPACE_DETECTION, null,
                        emptySpace, "An empty space appears present.", now),
                AiValidationFinding.of(AiValidationType.VEHICLE_FIT_ESTIMATION, null,
                        emptySpace, "Space appears to fit common vehicle sizes.", now),
                AiValidationFinding.of(AiValidationType.LEGAL_RISK_DETECTION, null,
                        legalRisk, "No obvious legal/placement risk detected.", now),
                AiValidationFinding.of(AiValidationType.IMAGE_QUALITY, null,
                        imageQuality, "Image quality is acceptable.", now),
                AiValidationFinding.of(AiValidationType.DUPLICATE_RISK, null,
                        duplicateRisk, "Low likelihood of being a duplicate submission.", now));

        List<VehicleFitEstimate> fits = List.of(
                VehicleFitEstimate.of(VehicleType.MOTORCYCLE, 100, now),
                VehicleFitEstimate.of(VehicleType.HATCHBACK, Score.clamp(emptySpace + 10), now),
                VehicleFitEstimate.of(VehicleType.SEDAN, emptySpace, now),
                VehicleFitEstimate.of(VehicleType.SUV, Score.clamp(emptySpace - 15), now),
                VehicleFitEstimate.of(VehicleType.VAN, Score.clamp(emptySpace - 30), now));

        return AiValidationResult.create(mediaId, parkingSpotId, requestedByUserId,
                emptySpace, legalRisk, imageQuality, aiConfidence, findings, fits, now);
    }

    private static int seedOf(UUID mediaId) {
        long mix = mediaId.getMostSignificantBits() ^ mediaId.getLeastSignificantBits();
        return (int) Math.floorMod(mix, 101);
    }
}
