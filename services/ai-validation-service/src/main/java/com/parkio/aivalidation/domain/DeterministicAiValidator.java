package com.parkio.aivalidation.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Placeholder validator that produces deterministic, advisory scores from simple
 * inputs. Scores are seeded from the media id and adjusted by
 * {@link ContentRiskClassifier} so non-parking / uncertain media fail closed for the
 * parking publication gate. Real vision I/O lives behind the classifier port.
 *
 * <p>Pure domain: no framework or provider SDK dependencies (ai-context/01).
 */
public final class DeterministicAiValidator {

    /** Finding detail prefix for infrastructure fail-closed (do not semantic-reuse). */
    public static final String VISION_OUTCOME_INFRA_PREFIX = "vision_outcome:INFRASTRUCTURE:";
    /** Finding detail prefix for genuine semantic UNCERTAIN (reuse within TTL). */
    public static final String VISION_OUTCOME_SEMANTIC_UNCERTAIN = "vision_outcome:SEMANTIC_UNCERTAIN";
    public static final String REASON_CODE_PREFIX = "reason_code:";
    public static final String REJECTION_REASON_CODE_PREFIX = "rejection_reason_code:";
    public static final String ASSESSMENT_CLAIMED_REGION_PREFIX = "assessment:claimed_region:";
    public static final String ASSESSMENT_VEHICLE_FIT_PREFIX = "assessment:vehicle_fit:";
    public static final String ASSESSMENT_OBSTRUCTION_PREFIX = "assessment:obstruction:";
    public static final String ASSESSMENT_LEGALITY_PREFIX = "assessment:legality:";
    public static final String MODERATION_DECISION_PREFIX = "moderation_decision:";
    public static final String MODERATION_SIGNAL_PREFIX = "moderation_signal:";
    public static final String REVIEW_EXPLANATION_KEY = "share.validation.reviewBody";

    private final ContentRiskClassifier contentRiskClassifier;

    public DeterministicAiValidator(ContentRiskClassifier contentRiskClassifier) {
        this.contentRiskClassifier = contentRiskClassifier == null
                ? mediaId -> ContentRiskClassifier.Verdict.UNCERTAIN
                : contentRiskClassifier;
    }

    /** Fail-closed default constructor (always UNCERTAIN). Prefer the classifier ctor. */
    public DeterministicAiValidator() {
        this(mediaId -> ContentRiskClassifier.Verdict.UNCERTAIN);
    }

    /**
     * Runs the placeholder checks and assembles an advisory result. The status is
     * derived by {@link AiValidationResult#create} via {@link AiValidationStatusPolicy}.
     */
    public AiValidationResult validate(UUID mediaId, UUID parkingSpotId, UUID requestedByUserId, Instant now) {
        int seed = seedOf(mediaId);
        ContentClassification classification = contentRiskClassifier.classifyDetailed(mediaId);
        ContentRiskClassifier.Verdict verdict = classification.verdict();

        int imageQuality = Score.clamp(70 + seed % 30);
        int emptySpace = Score.clamp(60 + seed % 40);
        int legalRisk = Score.clamp(seed % 30);
        int duplicateRisk = Score.clamp(seed % 20);
        int aiConfidence = Score.clamp(65 + seed % 35);

        List<AiValidationFinding> findings = new ArrayList<>(List.of(
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
                        duplicateRisk, "Low likelihood of being a duplicate submission.", now)));

        appendStructuredAssessments(findings, classification, now);
        appendModerationSignals(findings, classification, now);

        if (verdict == ContentRiskClassifier.Verdict.NOT_A_PARKING_SPOT) {
            imageQuality = 10;
            findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY,
                    AiRiskType.NOT_A_PARKING_SPOT, 100,
                    "Content does not appear to depict a usable parking spot in the claimed region.", now));
            findings.add(AiValidationFinding.of(AiValidationType.IMAGE_QUALITY,
                    AiRiskType.LOW_IMAGE_QUALITY, imageQuality,
                    "Image quality treated as unusable for non-parking content.", now));
        } else if (verdict == ContentRiskClassifier.Verdict.UNCERTAIN) {
            // Low confidence alone must never produce FAILED — keep WARNING path.
            aiConfidence = 40;
            findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                    40, "Uncertain whether the claimed region is a usable parking spot.", now));
            findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                    40, REVIEW_EXPLANATION_KEY, now));
            if (classification.outcomeKind() == ContentClassification.OutcomeKind.INFRASTRUCTURE) {
                String reason = classification.reasonCode() == null ? "unknown" : classification.reasonCode();
                findings.add(AiValidationFinding.of(AiValidationType.IMAGE_QUALITY, null,
                        40, VISION_OUTCOME_INFRA_PREFIX + reason, now));
            } else {
                findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                        40, VISION_OUTCOME_SEMANTIC_UNCERTAIN, now));
            }
        }

        List<VehicleFitEstimate> fits = List.of(
                VehicleFitEstimate.of(VehicleType.MOTORCYCLE, 100, now),
                VehicleFitEstimate.of(VehicleType.HATCHBACK, Score.clamp(emptySpace + 10), now),
                VehicleFitEstimate.of(VehicleType.SEDAN, emptySpace, now),
                VehicleFitEstimate.of(VehicleType.SUV, Score.clamp(emptySpace - 15), now),
                VehicleFitEstimate.of(VehicleType.VAN, Score.clamp(emptySpace - 30), now));

        ModerationProvenance provenance = classification.provenance() != null
                ? classification.provenance()
                : ModerationProvenance.heuristic();
        return AiValidationResult.create(mediaId, parkingSpotId, requestedByUserId,
                emptySpace, legalRisk, imageQuality, aiConfidence, findings, fits, provenance, now);
    }

    private static void appendStructuredAssessments(List<AiValidationFinding> findings,
                                                    ContentClassification classification,
                                                    Instant now) {
        if (classification.reasonCode() != null && !classification.reasonCode().isBlank()) {
            findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                    50, REASON_CODE_PREFIX + classification.reasonCode(), now));
        }
        if (classification.rejectionReasonCode() != null && !classification.rejectionReasonCode().isBlank()) {
            findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                    50, REJECTION_REASON_CODE_PREFIX + classification.rejectionReasonCode(), now));
        }
        if (classification.claimedRegionAssessment() != null) {
            findings.add(AiValidationFinding.of(AiValidationType.EMPTY_SPACE_DETECTION, null,
                    50, ASSESSMENT_CLAIMED_REGION_PREFIX + classification.claimedRegionAssessment(), now));
        }
        if (classification.vehicleFitEstimate() != null) {
            findings.add(AiValidationFinding.of(AiValidationType.VEHICLE_FIT_ESTIMATION, null,
                    50, ASSESSMENT_VEHICLE_FIT_PREFIX + classification.vehicleFitEstimate(), now));
        }
        if (classification.obstructionAssessment() != null) {
            findings.add(AiValidationFinding.of(AiValidationType.EMPTY_SPACE_DETECTION, null,
                    50, ASSESSMENT_OBSTRUCTION_PREFIX + classification.obstructionAssessment(), now));
        }
        if (classification.legalityAccessAssessment() != null) {
            findings.add(AiValidationFinding.of(AiValidationType.LEGAL_RISK_DETECTION, null,
                    50, ASSESSMENT_LEGALITY_PREFIX + classification.legalityAccessAssessment(), now));
        }
    }

    private static void appendModerationSignals(List<AiValidationFinding> findings,
                                                ContentClassification classification,
                                                Instant now) {
        if (classification.moderationDecision() != null) {
            findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                    50, MODERATION_DECISION_PREFIX + classification.moderationDecision().name(), now));
        }
        ModerationSignals signals = classification.moderationSignals();
        if (signals == null) {
            return;
        }
        findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                50, MODERATION_SIGNAL_PREFIX + "sceneType=" + signals.sceneType(), now));
        findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                50, MODERATION_SIGNAL_PREFIX + "roadContext=" + signals.roadContextPresent(), now));
        findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                50, MODERATION_SIGNAL_PREFIX + "parkingContext=" + signals.parkingContextPresent(), now));
        findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                50, MODERATION_SIGNAL_PREFIX + "openSpace=" + signals.vehicleSizedOpenSpacePresent(), now));
        findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                50, MODERATION_SIGNAL_PREFIX + "irrelevant=" + signals.clearlyIrrelevantContent(), now));
        findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                50, MODERATION_SIGNAL_PREFIX + "usable=" + signals.imageUsable(), now));
        findings.add(AiValidationFinding.of(AiValidationType.PARKING_SPACE_VISIBILITY, null,
                50, MODERATION_SIGNAL_PREFIX + "legalityConcern=" + signals.possibleSafetyOrLegalityConcern(), now));
    }

    private static int seedOf(UUID mediaId) {
        long mix = mediaId.getMostSignificantBits() ^ mediaId.getLeastSignificantBits();
        return (int) Math.floorMod(mix, 101);
    }
}
