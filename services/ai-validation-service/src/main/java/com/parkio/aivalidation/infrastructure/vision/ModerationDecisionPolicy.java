package com.parkio.aivalidation.infrastructure.vision;

import com.parkio.aivalidation.domain.ContentRiskClassifier;
import com.parkio.aivalidation.domain.ModerationDecision;
import com.parkio.aivalidation.domain.ModerationSignals;
import com.parkio.aivalidation.infrastructure.config.VisionProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Central three-way moderation policy. Thresholds are owned by
 * {@link VisionProperties#getDecision()} — do not duplicate them elsewhere.
 *
 * <p>Parkio philosophy: the AI is a <strong>spam filter first</strong> and a parking
 * validator second. Prefer {@link ModerationDecision#AUTO_ACCEPT} whenever the image
 * plausibly shows parking/road context. Reject only on extremely high-confidence
 * irrelevant or unusable content. Prefer recall over precision — false positives are
 * acceptable; false negatives discourage community contributions. Availability edge
 * cases are left to community validation.
 */
public final class ModerationDecisionPolicy {

    private static final Set<String> CLEARLY_IRRELEVANT_REASONS = Set.of(
            "UNRELATED_SUBJECT",
            "SCREENSHOT_OR_SYNTHETIC",
            "INDOOR_SCENE",
            "SELFIE_OR_PERSONAL_PHOTO",
            "FOOD_OR_RANDOM_OBJECT",
            "NO_ROAD_OR_PARKING_CONTEXT",
            "IMAGE_CORRUPTED");

    private static final Set<String> UNUSABLE_IMAGE_REASONS = Set.of(
            "TOO_DARK_OR_BLURRY",
            "IMAGE_TOO_DARK",
            "IMAGE_TOO_BLURRY",
            "UNUSABLE_IMAGE");

    /**
     * Reasons that indicate a parking/road scene even when the model is unsure
     * about availability or vehicle fit.
     */
    private static final Set<String> PARKING_CONTEXT_REASONS = Set.of(
            "CLEAR_USABLE_SPACE",
            "EMPTY_SPACE_VISIBLE",
            "POSSIBLE_SPACE_UNCERTAIN_WIDTH",
            "POSSIBLE_SPACE_UNCLEAR_ACCESS",
            "NEARBY_BARRIER_NOT_BLOCKING_TARGET",
            "WHOLE_IMAGE_NO_REGION",
            "LEGALITY_UNCERTAIN",
            "OTHER");

    /**
     * Soft model rejects that still describe a parking-related scene — prefer accept
     * (community owns availability). Excludes opaque {@code OTHER} hard rejects.
     */
    private static final Set<String> PARKING_RELATED_SOFT_REJECT_REASONS = Set.of(
            "CLEAR_USABLE_SPACE",
            "EMPTY_SPACE_VISIBLE",
            "POSSIBLE_SPACE_UNCERTAIN_WIDTH",
            "POSSIBLE_SPACE_UNCLEAR_ACCESS",
            "NEARBY_BARRIER_NOT_BLOCKING_TARGET",
            "WHOLE_IMAGE_NO_REGION");

    private ModerationDecisionPolicy() {
    }

    public record Result(
            ModerationDecision decision,
            ContentRiskClassifier.Verdict verdict,
            ModerationSignals signals,
            String rejectionReasonCode) {
        public Result(ModerationDecision decision, ContentRiskClassifier.Verdict verdict, ModerationSignals signals) {
            this(decision, verdict, signals, null);
        }
    }

    public static Result evaluate(VisionProviderClient.VisionAnalysis analysis, VisionProperties properties) {
        VisionProperties.Decision thresholds = properties.getDecision();
        String reasonCode = normalizeReason(analysis.reasonCode());
        double confidence = clampConfidence(analysis.confidence());

        boolean clearlyIrrelevantReason = CLEARLY_IRRELEVANT_REASONS.contains(reasonCode);
        boolean unusableReason = UNUSABLE_IMAGE_REASONS.contains(reasonCode);
        boolean parkingContextReason = PARKING_CONTEXT_REASONS.contains(reasonCode)
                || "LIKELY_PARKING".equals(analysis.verdict());

        double clearlyIrrelevantConfidence = clearlyIrrelevantReason && "NOT_A_PARKING_SPOT".equals(analysis.verdict())
                ? confidence
                : 0.0;
        double unusableImageConfidence = unusableReason && "NOT_A_PARKING_SPOT".equals(analysis.verdict())
                ? confidence
                : 0.0;

        boolean imageUsable = !unusableReason || confidence < thresholds.getUnusableImageRejectConfidence();
        boolean roadContextPresent = inferRoadContext(analysis, reasonCode);
        boolean parkingContextPresent = inferParkingContextPresent(analysis, reasonCode, parkingContextReason, clearlyIrrelevantReason);
        boolean vehicleSizedOpenSpacePresent = inferOpenSpace(analysis);
        boolean clearlyIrrelevantContent =
                clearlyIrrelevantConfidence >= thresholds.getClearlyIrrelevantRejectConfidence();
        boolean legalityConcern = "LEGALITY_UNCERTAIN".equals(reasonCode)
                || "CLEARLY_RESTRICTED_AREA".equals(reasonCode)
                || "RESTRICTED".equalsIgnoreCase(nullToEmpty(analysis.legalityAccessAssessment()));

        double parkingContextConfidence = parkingContextPresent ? confidenceForAccept(analysis, confidence) : 0.0;
        double vehicleSizedOpenSpaceConfidence = vehicleSizedOpenSpacePresent
                ? openSpaceConfidence(analysis, confidence)
                : 0.0;

        List<String> reasonCodes = new ArrayList<>();
        reasonCodes.add(reasonCode);

        ModerationSignals signals = new ModerationSignals(
                sceneTypeOf(analysis, reasonCode),
                roadContextPresent,
                parkingContextPresent,
                vehicleSizedOpenSpacePresent,
                clearlyIrrelevantContent,
                imageUsable,
                legalityConcern,
                parkingContextConfidence,
                vehicleSizedOpenSpaceConfidence,
                clearlyIrrelevantConfidence,
                unusableImageConfidence,
                reasonCodes);

        ModerationDecision decision = decide(analysis, signals, thresholds, unusableReason, reasonCode);
        ContentRiskClassifier.Verdict verdict = mapVerdict(decision);
        String rejectionReasonCode = decision == ModerationDecision.AUTO_REJECT
                ? mapRejectionReasonCode(reasonCode)
                : null;
        return new Result(decision, verdict, signals, rejectionReasonCode);
    }

    /**
     * Maps a vision reason code to a controlled product rejection code. Only used for
     * {@link ModerationDecision#AUTO_REJECT}.
     */
    static String mapRejectionReasonCode(String visionReasonCode) {
        String reason = normalizeReason(visionReasonCode);
        return switch (reason) {
            case "INDOOR_SCENE" -> "INDOOR_SCENE";
            case "SELFIE_OR_PERSONAL_PHOTO" -> "SELFIE_OR_PERSONAL_PHOTO";
            case "FOOD_OR_RANDOM_OBJECT" -> "FOOD_OR_RANDOM_OBJECT";
            case "SCREENSHOT_OR_SYNTHETIC", "SCREENSHOT_OR_DOCUMENT" -> "SCREENSHOT_OR_DOCUMENT";
            case "NO_ROAD_OR_PARKING_CONTEXT" -> "NO_ROAD_OR_PARKING_CONTEXT";
            case "IMAGE_TOO_DARK" -> "IMAGE_TOO_DARK";
            case "IMAGE_TOO_BLURRY" -> "IMAGE_TOO_BLURRY";
            case "IMAGE_CORRUPTED" -> "IMAGE_CORRUPTED";
            case "TOO_DARK_OR_BLURRY", "UNUSABLE_IMAGE" -> "UNUSABLE_IMAGE";
            case "UNRELATED_SUBJECT" -> "CLEARLY_UNRELATED_CONTENT";
            default -> "CLEARLY_UNRELATED_CONTENT";
        };
    }

    /**
     * Recall-first three-way gate:
     * <ol>
     *   <li>{@link ModerationDecision#AUTO_REJECT} only for extremely high-confidence
     *       spam / unusable frames</li>
     *   <li>{@link ModerationDecision#MANUAL_REVIEW} for quality/legality ambiguity</li>
     *   <li>{@link ModerationDecision#AUTO_ACCEPT} when parking or road context is
     *       plausible — open-space / vehicle-fit certainty is <em>not</em> required</li>
     * </ol>
     */
    private static ModerationDecision decide(
            VisionProviderClient.VisionAnalysis analysis,
            ModerationSignals signals,
            VisionProperties.Decision thresholds,
            boolean unusableReason,
            String reasonCode) {
        // Spam filter: reject only when confidence is extremely high.
        if (signals.clearlyIrrelevantConfidence() >= thresholds.getClearlyIrrelevantRejectConfidence()
                || signals.unusableImageConfidence() >= thresholds.getUnusableImageRejectConfidence()) {
            return ModerationDecision.AUTO_REJECT;
        }

        // Blurry / dark / quality-limited frames stay in review when not rejected.
        if (unusableReason) {
            return ModerationDecision.MANUAL_REVIEW;
        }

        // Genuine legality ambiguity — do not auto-publish restricted-looking scenes.
        if (signals.possibleSafetyOrLegalityConcern()) {
            return ModerationDecision.MANUAL_REVIEW;
        }

        double acceptFloor = thresholds.getParkingContextAcceptConfidence();
        double modelConfidence = clampConfidence(analysis.confidence());

        // Model affirms parking — accept without requiring open-space certainty.
        if ("LIKELY_PARKING".equals(analysis.verdict())
                && signals.clearlyIrrelevantConfidence() < thresholds.getClearlyIrrelevantAcceptMax()
                && modelConfidence >= acceptFloor) {
            return ModerationDecision.AUTO_ACCEPT;
        }

        // Uncertain / soft parking-context reasons with enough context confidence → accept.
        if (signals.parkingContextPresent()
                && signals.clearlyIrrelevantConfidence() < thresholds.getClearlyIrrelevantAcceptMax()
                && signals.parkingContextConfidence() >= acceptFloor) {
            return ModerationDecision.AUTO_ACCEPT;
        }

        // Soft reject that still describes parking-related content → prefer accept.
        if ("NOT_A_PARKING_SPOT".equals(analysis.verdict())
                && PARKING_RELATED_SOFT_REJECT_REASONS.contains(reasonCode)
                && signals.clearlyIrrelevantConfidence() < thresholds.getClearlyIrrelevantAcceptMax()
                && modelConfidence >= acceptFloor) {
            return ModerationDecision.AUTO_ACCEPT;
        }

        return ModerationDecision.MANUAL_REVIEW;
    }

    private static boolean inferParkingContextPresent(
            VisionProviderClient.VisionAnalysis analysis,
            String reasonCode,
            boolean parkingContextReason,
            boolean clearlyIrrelevantReason) {
        if ("LIKELY_PARKING".equals(analysis.verdict())) {
            return true;
        }
        if (clearlyIrrelevantReason) {
            return false;
        }
        // Opaque OTHER hard-rejects are not treated as parking context.
        if ("NOT_A_PARKING_SPOT".equals(analysis.verdict()) && "OTHER".equals(reasonCode)) {
            return false;
        }
        if ("NOT_A_PARKING_SPOT".equals(analysis.verdict())) {
            return PARKING_RELATED_SOFT_REJECT_REASONS.contains(reasonCode);
        }
        return parkingContextReason;
    }

    private static ContentRiskClassifier.Verdict mapVerdict(ModerationDecision decision) {
        return switch (decision) {
            case AUTO_ACCEPT -> ContentRiskClassifier.Verdict.LIKELY_PARKING;
            case AUTO_REJECT -> ContentRiskClassifier.Verdict.NOT_A_PARKING_SPOT;
            case MANUAL_REVIEW -> ContentRiskClassifier.Verdict.UNCERTAIN;
        };
    }

    private static boolean inferRoadContext(VisionProviderClient.VisionAnalysis analysis, String reasonCode) {
        if (CLEARLY_IRRELEVANT_REASONS.contains(reasonCode)) {
            return false;
        }
        return !"NOT_A_PARKING_SPOT".equals(analysis.verdict())
                || UNUSABLE_IMAGE_REASONS.contains(reasonCode);
    }

    private static boolean inferOpenSpace(VisionProviderClient.VisionAnalysis analysis) {
        if ("LIKELY_PARKING".equals(analysis.verdict())) {
            return true;
        }
        String fit = nullToEmpty(analysis.vehicleFitEstimate()).toUpperCase(Locale.ROOT);
        if ("FITS".equals(fit) || "TIGHT".equals(fit) || "UNCERTAIN".equals(fit)) {
            return true;
        }
        String region = nullToEmpty(analysis.claimedRegionAssessment()).toUpperCase(Locale.ROOT);
        return "FREE".equals(region) || "UNCERTAIN".equals(region);
    }

    private static double confidenceForAccept(VisionProviderClient.VisionAnalysis analysis, double confidence) {
        if ("LIKELY_PARKING".equals(analysis.verdict())) {
            return confidence;
        }
        String reason = normalizeReason(analysis.reasonCode());
        // Opaque OTHER stays inconclusive unless raw confidence clears the accept floor.
        if ("OTHER".equals(reason)) {
            return confidence;
        }
        // Soft floor so named parking-context reasons under UNCERTAIN / soft reject can accept.
        return Math.max(0.45, confidence * 0.85);
    }

    private static double openSpaceConfidence(VisionProviderClient.VisionAnalysis analysis, double confidence) {
        if ("LIKELY_PARKING".equals(analysis.verdict())) {
            return confidence;
        }
        String fit = nullToEmpty(analysis.vehicleFitEstimate()).toUpperCase(Locale.ROOT);
        return switch (fit) {
            case "FITS" -> Math.max(confidence, 0.75);
            case "TIGHT" -> Math.max(confidence * 0.9, 0.6);
            case "UNCERTAIN" -> Math.max(confidence * 0.7, 0.45);
            default -> confidence * 0.5;
        };
    }

    private static String sceneTypeOf(VisionProviderClient.VisionAnalysis analysis, String reasonCode) {
        if (CLEARLY_IRRELEVANT_REASONS.contains(reasonCode)) {
            return "irrelevant";
        }
        if (UNUSABLE_IMAGE_REASONS.contains(reasonCode)) {
            return "unusable";
        }
        if ("LIKELY_PARKING".equals(analysis.verdict())) {
            return "outdoor_parking_context";
        }
        return "ambiguous_road_or_parking";
    }

    private static String normalizeReason(String reasonCode) {
        return reasonCode == null || reasonCode.isBlank() ? "OTHER" : reasonCode;
    }

    private static double clampConfidence(double confidence) {
        if (Double.isNaN(confidence)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
