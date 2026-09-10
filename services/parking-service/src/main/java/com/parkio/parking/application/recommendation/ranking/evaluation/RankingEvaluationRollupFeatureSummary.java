package com.parkio.parking.application.recommendation.ranking.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;

/** Derive coarse privacy-safe rollup dimensions from allowlisted feature JSON. */
final class RankingEvaluationRollupFeatureSummary {

    final String freshnessMix;
    final boolean zeroAvailabilityPresent;
    final boolean highCapacityPresent;
    final boolean[] zeroAvailabilityByOrdinal;
    final boolean[] staleStaticByOrdinal;

    private RankingEvaluationRollupFeatureSummary(
            String freshnessMix,
            boolean zeroAvailabilityPresent,
            boolean highCapacityPresent,
            boolean[] zeroAvailabilityByOrdinal,
            boolean[] staleStaticByOrdinal) {
        this.freshnessMix = freshnessMix;
        this.zeroAvailabilityPresent = zeroAvailabilityPresent;
        this.highCapacityPresent = highCapacityPresent;
        this.zeroAvailabilityByOrdinal = zeroAvailabilityByOrdinal;
        this.staleStaticByOrdinal = staleStaticByOrdinal;
    }

    static RankingEvaluationRollupFeatureSummary parse(
            ObjectMapper mapper, String featuresJson, int candidateCount) {
        boolean[] zero = new boolean[Math.max(candidateCount, 0)];
        boolean[] stale = new boolean[Math.max(candidateCount, 0)];
        boolean anyZero = false;
        boolean anyHighCap = false;
        boolean anyLive = false;
        boolean anyStale = false;
        try {
            JsonNode root = mapper.readTree(featuresJson == null ? "[]" : featuresJson);
            if (root != null && root.isArray()) {
                for (JsonNode node : root) {
                    if (node == null || !node.isObject()) {
                        continue;
                    }
                    int ordinal = node.path("candidateOrdinal").asInt(-1);
                    String freshness = text(node, "occupancyFreshnessKind");
                    String availability = text(node, "availabilityBucket");
                    String capacity = text(node, "capacityBucket");
                    boolean isZero = availability.contains("ZERO") || availability.equals("NONE");
                    boolean isStale = freshness.contains("STALE")
                            || freshness.contains("STATIC")
                            || freshness.contains("AGING")
                            || freshness.contains("UNAVAILABLE");
                    boolean isLive = freshness.contains("LIVE");
                    if (ordinal >= 0 && ordinal < zero.length) {
                        zero[ordinal] = isZero;
                        stale[ordinal] = isStale;
                    }
                    anyZero |= isZero;
                    anyHighCap |= capacity.contains("HIGH");
                    anyLive |= isLive;
                    anyStale |= isStale;
                }
            }
        } catch (Exception ignored) {
            // Fail closed to UNKNOWN mix; do not block rollup.
        }
        String mix;
        if (anyLive && anyStale) {
            mix = "MIXED";
        } else if (anyLive) {
            mix = "LIVE_ONLY";
        } else if (anyStale) {
            mix = "STALE_STATIC";
        } else {
            mix = "UNKNOWN";
        }
        return new RankingEvaluationRollupFeatureSummary(mix, anyZero, anyHighCap, zero, stale);
    }

    boolean zeroAt(int ordinal) {
        return ordinal >= 0 && ordinal < zeroAvailabilityByOrdinal.length && zeroAvailabilityByOrdinal[ordinal];
    }

    boolean staleAt(int ordinal) {
        return ordinal >= 0 && ordinal < staleStaticByOrdinal.length && staleStaticByOrdinal[ordinal];
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("").toUpperCase(Locale.ROOT);
    }
}
