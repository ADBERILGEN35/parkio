package com.parkio.parking.decision.normalization;

import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidencePolarity;
import com.parkio.parking.decision.evidence.EvidenceSource;
import com.parkio.parking.decision.evidence.EvidenceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Maps {@link AiValidationEvidenceInput} to canonical {@link EvidenceItem} values.
 * Pure, deterministic, side-effect free.
 */
public final class AiValidationEvidenceNormalizer {

    private static final Comparator<EvidenceItem> DETERMINISTIC_ORDER =
            Comparator.comparing((EvidenceItem item) -> item.type().name())
                    .thenComparing(item -> item.source().name())
                    .thenComparing(item -> item.reasonCode().map(ReasonCode::value).orElse(""))
                    .thenComparing(item -> item.sourceReference().orElse(""))
                    .thenComparingInt(EvidenceItem::strength);

    public List<EvidenceItem> normalize(AiValidationEvidenceInput input) {
        Instant observedAt = input.occurredAt();
        String sourceRef = input.eventId().toString();
        List<EvidenceItem> items = new ArrayList<>();

        items.add(mapStatus(input.status(), observedAt, sourceRef));
        addScoreIfPresent(items, input.emptySpaceConfidence(), observedAt, sourceRef,
                ReasonCode.of("EMPTY_SPACE_CONFIDENCE"), EvidencePolarity.SUPPORTS_PUBLISH);
        addRiskScoreIfPresent(items, input.legalRiskScore(), observedAt, sourceRef);
        addScoreIfPresent(items, input.imageQualityScore(), observedAt, sourceRef,
                ReasonCode.of("IMAGE_QUALITY_SCORE"), EvidencePolarity.SUPPORTS_PUBLISH);
        addScoreIfPresent(items, input.aiConfidence(), observedAt, sourceRef,
                ReasonCode.of("AI_CONFIDENCE"), EvidencePolarity.NEUTRAL);

        Set<String> risks = new LinkedHashSet<>();
        for (String risk : input.detectedRiskTypes()) {
            if (risk != null && !risk.isBlank()) {
                risks.add(risk.trim().toUpperCase(Locale.ROOT));
            }
        }
        for (String risk : risks) {
            items.add(mapRiskType(risk, observedAt, sourceRef));
        }

        items.sort(DETERMINISTIC_ORDER);
        return List.copyOf(items);
    }

    private static EvidenceItem mapStatus(String status, Instant observedAt, String sourceRef) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PASSED" -> EvidenceItem.of(
                    EvidenceType.AI_CONTENT_ANALYSIS,
                    EvidenceSource.AI_VALIDATION_SERVICE,
                    EvidencePolarity.SUPPORTS_PUBLISH,
                    55,
                    observedAt,
                    ReasonCode.of("AI_STATUS_PASSED"),
                    sourceRef);
            case "WARNING" -> EvidenceItem.of(
                    EvidenceType.AI_CONTENT_ANALYSIS,
                    EvidenceSource.AI_VALIDATION_SERVICE,
                    EvidencePolarity.NEUTRAL,
                    50,
                    observedAt,
                    ReasonCode.of("AI_STATUS_WARNING"),
                    sourceRef);
            case "FAILED" -> EvidenceItem.of(
                    EvidenceType.AI_CONTENT_ANALYSIS,
                    EvidenceSource.AI_VALIDATION_SERVICE,
                    EvidencePolarity.OPPOSES_PUBLISH,
                    75,
                    observedAt,
                    ReasonCode.of("AI_STATUS_FAILED"),
                    sourceRef);
            default -> EvidenceItem.of(
                    EvidenceType.AI_CONTENT_ANALYSIS,
                    EvidenceSource.AI_VALIDATION_SERVICE,
                    EvidencePolarity.NEUTRAL,
                    0,
                    observedAt,
                    ReasonCode.of("AI_STATUS_UNKNOWN"),
                    sourceRef);
        };
    }

    private static void addScoreIfPresent(
            List<EvidenceItem> items,
            Integer rawScore,
            Instant observedAt,
            String sourceRef,
            ReasonCode reasonCode,
            EvidencePolarity polarity) {
        if (rawScore == null) {
            return;
        }
        int score = requireScoreInRange(rawScore, reasonCode.value());
        items.add(EvidenceItem.of(
                EvidenceType.AI_CONTENT_ANALYSIS,
                EvidenceSource.AI_VALIDATION_SERVICE,
                polarity,
                score,
                observedAt,
                reasonCode,
                sourceRef));
    }

    private static void addRiskScoreIfPresent(
            List<EvidenceItem> items,
            Integer rawScore,
            Instant observedAt,
            String sourceRef) {
        if (rawScore == null) {
            return;
        }
        int score = requireScoreInRange(rawScore, "LEGAL_RISK_SCORE");
        items.add(EvidenceItem.of(
                EvidenceType.AI_CONTENT_ANALYSIS,
                EvidenceSource.AI_VALIDATION_SERVICE,
                EvidencePolarity.OPPOSES_PUBLISH,
                score,
                observedAt,
                ReasonCode.of("LEGAL_RISK_SCORE"),
                sourceRef));
    }

    private static EvidenceItem mapRiskType(String risk, Instant observedAt, String sourceRef) {
        ReasonCode reason = ReasonCode.of("AI_RISK_" + sanitizeReasonSuffix(risk));
        return switch (risk) {
            case "LOW_IMAGE_QUALITY" -> EvidenceItem.of(
                    EvidenceType.AI_CONTENT_ANALYSIS,
                    EvidenceSource.AI_VALIDATION_SERVICE,
                    EvidencePolarity.NEUTRAL,
                    60,
                    observedAt,
                    reason,
                    sourceRef);
            case "NOT_A_PARKING_SPOT" -> EvidenceItem.of(
                    EvidenceType.AI_CONTENT_ANALYSIS,
                    EvidenceSource.AI_VALIDATION_SERVICE,
                    EvidencePolarity.OPPOSES_PUBLISH,
                    90,
                    observedAt,
                    reason,
                    sourceRef);
            case "NO_PARKING_SIGN", "GARAGE_ENTRANCE", "BUS_STOP", "PEDESTRIAN_CROSSING", "FIRE_HYDRANT",
                    "SIDEWALK", "TRAFFIC_FLOW_BLOCKING", "PRIVATE_PROPERTY" -> EvidenceItem.of(
                    EvidenceType.AI_CONTENT_ANALYSIS,
                    EvidenceSource.AI_VALIDATION_SERVICE,
                    EvidencePolarity.OPPOSES_PUBLISH,
                    70,
                    observedAt,
                    reason,
                    sourceRef);
            default -> EvidenceItem.of(
                    EvidenceType.AI_CONTENT_ANALYSIS,
                    EvidenceSource.AI_VALIDATION_SERVICE,
                    EvidencePolarity.NEUTRAL,
                    40,
                    observedAt,
                    ReasonCode.of("AI_RISK_UNKNOWN"),
                    sourceRef);
        };
    }

    private static int requireScoreInRange(int rawScore, String fieldName) {
        if (rawScore < 0 || rawScore > 100) {
            throw new EvidenceNormalizationException(
                    fieldName + " must be between 0 and 100 inclusive, was " + rawScore);
        }
        return rawScore;
    }

    private static String sanitizeReasonSuffix(String risk) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < risk.length(); i++) {
            char c = risk.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                builder.append(c);
            } else if (c == '-' || c == ' ') {
                builder.append('_');
            }
        }
        String suffix = builder.toString();
        return suffix.isEmpty() ? "UNSPECIFIED" : suffix;
    }
}
