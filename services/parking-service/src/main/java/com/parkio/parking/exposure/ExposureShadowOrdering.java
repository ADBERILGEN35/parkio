package com.parkio.parking.exposure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic shadow ordering and legacy comparison helpers. */
public final class ExposureShadowOrdering {

    private ExposureShadowOrdering() {
    }

    public static List<ShadowExposurePosition> shadowOrder(List<ExposureEvaluation> evaluations) {
        Objects.requireNonNull(evaluations, "evaluations");
        List<ExposureEvaluation> sorted = evaluations.stream()
                .sorted(comparator())
                .toList();
        List<ShadowExposurePosition> result = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            ExposureEvaluation evaluation = sorted.get(i);
            result.add(new ShadowExposurePosition(
                    i + 1,
                    evaluation.evidence().candidateId(),
                    evaluation));
        }
        return result;
    }

    public static List<LegacySearchPosition> legacyOrder(List<ExposureCandidateId> candidateIds) {
        Objects.requireNonNull(candidateIds, "candidateIds");
        List<LegacySearchPosition> result = new ArrayList<>(candidateIds.size());
        for (int i = 0; i < candidateIds.size(); i++) {
            result.add(new LegacySearchPosition(i + 1, candidateIds.get(i)));
        }
        return result;
    }

    public static ExposureComparison compare(
            ExposureQueryContext queryContext,
            String policyVersion,
            ExposureSnapshotSchemaVersion schemaVersion,
            List<ExposureCandidateId> legacyCandidateOrder,
            List<ExposureEvaluation> evaluations,
            java.time.Instant comparedAt) {
        List<LegacySearchPosition> legacy = legacyOrder(legacyCandidateOrder);
        List<ShadowExposurePosition> shadow = shadowOrder(evaluations);

        boolean sameTop1 = !legacy.isEmpty()
                && !shadow.isEmpty()
                && legacy.getFirst().candidateId().equals(shadow.getFirst().candidateId());

        boolean sameTop3Order = topNOrderEqual(legacy, shadow, 3);
        boolean sameTop3Set = topNSetEqual(legacy, shadow, 3);

        Map<ExposureCandidateId, Integer> legacyRanks = rankMap(legacy);
        Map<ExposureCandidateId, Integer> shadowRanks = rankMap(shadow);

        int movementSum = 0;
        int movementCount = 0;
        int maxMovement = 0;
        int promoted = 0;
        int demoted = 0;
        int ineligibleInLegacy = 0;

        for (ExposureEvaluation evaluation : evaluations) {
            ExposureCandidateId id = evaluation.evidence().candidateId();
            Integer legacyRank = legacyRanks.get(id);
            Integer shadowRank = shadowRanks.get(id);
            if (legacyRank == null || shadowRank == null) {
                continue;
            }
            int movement = Math.abs(legacyRank - shadowRank);
            movementSum += movement;
            movementCount++;
            maxMovement = Math.max(maxMovement, movement);
            if (shadowRank < legacyRank) {
                promoted++;
            } else if (shadowRank > legacyRank) {
                demoted++;
            }
            if (!evaluation.eligible()) {
                ineligibleInLegacy++;
            }
        }

        int meanMovement = movementCount == 0 ? 0 : (movementSum + movementCount / 2) / movementCount;

        return new ExposureComparison(
                policyVersion,
                schemaVersion,
                queryContext,
                legacyCandidateOrder.size(),
                sameTop1,
                sameTop3Order,
                sameTop3Set,
                meanMovement,
                maxMovement,
                promoted,
                demoted,
                ineligibleInLegacy,
                movementBand(maxMovement),
                legacy,
                shadow,
                comparedAt);
    }

    private static Comparator<ExposureEvaluation> comparator() {
        return Comparator
                .comparing(ExposureEvaluation::eligible).reversed()
                .thenComparing((ExposureEvaluation e) -> e.score().total()).reversed()
                .thenComparing(e -> e.evidence().distanceMeters())
                .thenComparing(e -> publicationSortKey(e.evidence().publicationQuality()), Comparator.reverseOrder())
                .thenComparing(e -> e.evidence().candidateId().value());
    }

    private static long publicationSortKey(ExposurePublicationQuality quality) {
        return switch (quality) {
            case VERIFIED -> 2L;
            case ACTIVE -> 1L;
            case OTHER -> 0L;
        };
    }

    private static boolean topNOrderEqual(
            List<LegacySearchPosition> legacy,
            List<ShadowExposurePosition> shadow,
            int n) {
        int limit = Math.min(n, Math.min(legacy.size(), shadow.size()));
        for (int i = 0; i < limit; i++) {
            if (!legacy.get(i).candidateId().equals(shadow.get(i).candidateId())) {
                return false;
            }
        }
        return true;
    }

    private static boolean topNSetEqual(
            List<LegacySearchPosition> legacy,
            List<ShadowExposurePosition> shadow,
            int n) {
        Set<ExposureCandidateId> legacyTop = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(n, legacy.size()); i++) {
            legacyTop.add(legacy.get(i).candidateId());
        }
        Set<ExposureCandidateId> shadowTop = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(n, shadow.size()); i++) {
            shadowTop.add(shadow.get(i).candidateId());
        }
        return legacyTop.equals(shadowTop);
    }

    private static Map<ExposureCandidateId, Integer> rankMap(List<?> positions) {
        Map<ExposureCandidateId, Integer> ranks = new HashMap<>();
        for (int i = 0; i < positions.size(); i++) {
            ExposureCandidateId id = switch (positions.get(i)) {
                case LegacySearchPosition legacy -> legacy.candidateId();
                case ShadowExposurePosition shadow -> shadow.candidateId();
                default -> throw new IllegalStateException("unexpected position type");
            };
            ranks.put(id, i + 1);
        }
        return ranks;
    }

    public static String movementBand(int maxMovement) {
        if (maxMovement == 0) {
            return "NONE";
        }
        if (maxMovement <= 1) {
            return "LOW";
        }
        if (maxMovement <= 3) {
            return "MEDIUM";
        }
        if (maxMovement <= 5) {
            return "HIGH";
        }
        return "VERY_HIGH";
    }

    public static String scoreBand(int score) {
        if (score >= 7_500) {
            return "VERY_HIGH";
        }
        if (score >= 5_000) {
            return "HIGH";
        }
        if (score >= 3_000) {
            return "MEDIUM";
        }
        if (score >= 1_000) {
            return "LOW";
        }
        return "VERY_LOW";
    }
}
