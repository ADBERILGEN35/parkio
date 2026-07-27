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
 * {@link VisionProperties#getDecision()}   do not duplicate them elsewhere.
 */
public final class ModerationDecisionPolicy {

    private static final Set<String> CLEARLY_IRRELEVANT_REASONS = Set.of(
            "UNRELATED_SUBJECT",
            "SCREENSHOT_OR_SYNTHETIC");

    private static final Set<String> UNUSABLE_IMAGE_REASONS = Set.of("TOO_DARK_OR_BLURRY");

    private static final Set<String> PARKING_CONTEXT_REASONS = Set.of(
            "CLEAR_USABLE_SPACE",
            "POSSIBLE_SPACE_UNCERTAIN_WIDTH",
            "POSSIBLE_SPACE_UNCLEAR_ACCESS",
            "NEARBY_BARRIER_NOT_BLOCKING_TARGET",
            "WHOLE_IMAGE_NO_REGION",
            "LEGALITY_UNCERTAIN",
            "OTHER");

    private ModerationDecisionPolicy() {
    }

    public record Result(
            ModerationDecision decision,
            ContentRiskClassifier.Verdict verdict,
            ModerationSignals signals) {
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
        boolean parkingContextPresent = "LIKELY_PARKING".equals(analysis.verdict())
                || (parkingContextReason && !clearlyIrrelevantReason);
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

        ModerationDecision decision = decide(analysis, signals, thresholds, imageUsable);
        ContentRiskClassifier.Verdict verdict = mapVerdict(decision);
        return new Result(decision, verdict, signals);
    }

    private static ModerationDecision decide(
            VisionProviderClient.VisionAnalysis analysis,
            ModerationSignals signals,
            VisionProperties.Decision thresholds,
            boolean imageUsable) {
        if (signals.clearlyIrrelevantConfidence() >= thresholds.getClearlyIrrelevantRejectConfidence()
                || signals.unusableImageConfidence() >= thresholds.getUnusableImageRejectConfidence()) {
            return ModerationDecision.AUTO_REJECT;
        }
        if (!"LIKELY_PARKING".equals(analysis.verdict())) {
            return ModerationDecision.MANUAL_REVIEW;
        }
        if (imageUsable
                && signals.parkingContextConfidence() >= thresholds.getParkingContextAcceptConfidence()
                && signals.vehicleSizedOpenSpaceConfidence() >= thresholds.getOpenSpaceAcceptConfidence()
                && signals.clearlyIrrelevantConfidence() < thresholds.getClearlyIrrelevantAcceptMax()) {
            return ModerationDecision.AUTO_ACCEPT;
        }
        return ModerationDecision.MANUAL_REVIEW;
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
        return Math.max(0.55, confidence * 0.85);
    }

    private static double openSpaceConfidence(VisionProviderClient.VisionAnalysis analysis, double confidence) {
        if ("LIKELY_PARKING".equals(analysis.verdict())) {
            return confidence;
        }
        String fit = nullToEmpty(analysis.vehicleFitEstimate()).toUpperCase(Locale.ROOT);
        return switch (fit) {
            case "FITS" -> Math.max(confidence, 0.75);
            case "TIGHT" -> Math.max(confidence * 0.9, 0.6);
            case "UNCERTAIN" -> Math.max(confidence * 0.7, 0.55);
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
