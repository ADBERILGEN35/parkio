package com.parkio.parking.application.recommendation.ranking.shadow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Local feature-bucket challenger for the WP-SPA-14 shadow framework.
 * Freshness-heavy weights. Never networks. Does <strong>not</strong> implement
 * {@link com.parkio.parking.application.recommendation.ranking.ParkingCandidateRanker}.
 */
@Component
public class LocalChallengerShadowParkingRanker implements ShadowParkingRanker {

    // Freshness-heavy challenger — intentionally different from deterministic public weights.
    static final double W_FRESHNESS = 0.40;
    static final double W_DISTANCE = 0.20;
    static final double W_CAPACITY = 0.15;
    static final double W_CONFIDENCE = 0.15;
    static final double W_FAVOURITE = 0.10;
    static final int MAX_REASON_CATEGORIES = 2;

    @Override
    public ShadowRankingOutput rank(ShadowRankingRequest request) {
        Objects.requireNonNull(request, "request");
        List<Scored> scored = new ArrayList<>(request.candidates().size());
        Map<String, List<ShadowReasonCategory>> reasons = new HashMap<>();
        for (ShadowCandidateFeatures features : request.candidates()) {
            FactorScores factors = scoreFactors(features);
            double total = W_FRESHNESS * factors.freshness()
                    + W_DISTANCE * factors.distance()
                    + W_CAPACITY * factors.capacity()
                    + W_CONFIDENCE * factors.confidence()
                    + W_FAVOURITE * factors.favourite();
            if (!Double.isFinite(total) || total < 0.0) {
                total = 0.0;
            }
            scored.add(new Scored(features.alias(), total));
            reasons.put(features.alias(), topCategories(factors));
        }
        scored.sort(Comparator.comparingDouble(Scored::score)
                .reversed()
                .thenComparing(Scored::alias));
        List<String> ordered = scored.stream().map(Scored::alias).toList();
        ShadowConfidence confidence = confidenceFor(request.candidateCount(), scored);
        return new ShadowRankingOutput(
                ShadowRankingConstants.OUTPUT_SCHEMA_VERSION, ordered, confidence, reasons);
    }

    static FactorScores scoreFactors(ShadowCandidateFeatures features) {
        return new FactorScores(
                distanceScore(features.distanceBucket(), features.distanceNormalized()),
                freshnessScore(features.occupancyFreshnessKind()),
                capacityScore(features.availabilityBucket(), features.availabilityRatioBucket(), features.capacityBucket()),
                confidenceScore(features.inventoryConfidenceBucket()),
                features.isFavourite() ? 1.0 : 0.0);
    }

    static double distanceScore(String bucket, double normalized) {
        double fromBucket = switch (bucket == null ? "" : bucket) {
            case "0_200" -> 1.0;
            case "200_500" -> 0.75;
            case "500_1200" -> 0.4;
            case "1200_plus" -> 0.1;
            default -> 0.0;
        };
        double fromNorm = 1.0 - Math.min(Math.max(normalized, 0.0), 1.0);
        return Math.max(fromBucket, fromNorm * 0.9);
    }

    static double freshnessScore(String kind) {
        return switch (kind == null ? "" : kind) {
            case "LIVE" -> 1.0;
            case "COMMUNITY" -> 0.7;
            case "STALE" -> 0.25;
            case "STATIC" -> 0.15;
            default -> 0.0;
        };
    }

    static double capacityScore(String availability, String ratio, String capacity) {
        double avail = switch (availability == null ? "" : availability) {
            case "HIGH" -> 1.0;
            case "MED" -> 0.65;
            case "LOW" -> 0.3;
            case "ZERO" -> 0.0;
            default -> 0.0;
        };
        double ratioScore = switch (ratio == null ? "" : ratio) {
            case "HIGH" -> 1.0;
            case "MED" -> 0.6;
            case "LOW" -> 0.25;
            case "ZERO" -> 0.0;
            default -> 0.0;
        };
        double capacityBoost = switch (capacity == null ? "" : capacity) {
            case "51_plus" -> 0.2;
            case "11_50" -> 0.1;
            default -> 0.0;
        };
        return Math.min(1.0, 0.6 * Math.max(avail, ratioScore) + 0.4 * ratioScore + capacityBoost);
    }

    static double confidenceScore(String bucket) {
        return switch (bucket == null ? "" : bucket) {
            case "75_100", "HIGH" -> 1.0;
            case "50_75", "MED" -> 0.7;
            case "25_50", "LOW" -> 0.4;
            case "0_25" -> 0.15;
            default -> 0.0;
        };
    }

    static List<ShadowReasonCategory> topCategories(FactorScores factors) {
        List<CategoryScore> ranked = new ArrayList<>(5);
        ranked.add(new CategoryScore(ShadowReasonCategory.FRESHNESS, factors.freshness() * W_FRESHNESS));
        ranked.add(new CategoryScore(ShadowReasonCategory.DISTANCE, factors.distance() * W_DISTANCE));
        ranked.add(new CategoryScore(ShadowReasonCategory.CAPACITY, factors.capacity() * W_CAPACITY));
        ranked.add(new CategoryScore(ShadowReasonCategory.CONFIDENCE, factors.confidence() * W_CONFIDENCE));
        ranked.add(new CategoryScore(ShadowReasonCategory.FAVOURITE, factors.favourite() * W_FAVOURITE));
        ranked.sort(Comparator.comparingDouble(CategoryScore::score)
                .reversed()
                .thenComparing(c -> c.category().name()));
        List<ShadowReasonCategory> top = new ArrayList<>(MAX_REASON_CATEGORIES);
        for (CategoryScore entry : ranked) {
            if (entry.score() <= 0.0) {
                continue;
            }
            top.add(entry.category());
            if (top.size() >= MAX_REASON_CATEGORIES) {
                break;
            }
        }
        if (top.isEmpty()) {
            top.add(ShadowReasonCategory.DISTANCE);
        }
        return List.copyOf(top);
    }

    private static ShadowConfidence confidenceFor(int candidateCount, List<Scored> scored) {
        if (candidateCount <= 1 || scored.size() <= 1) {
            return ShadowConfidence.HIGH;
        }
        double gap = scored.get(0).score() - scored.get(1).score();
        if (gap >= 0.15) {
            return ShadowConfidence.HIGH;
        }
        if (gap >= 0.05) {
            return ShadowConfidence.MEDIUM;
        }
        return ShadowConfidence.LOW;
    }

    record FactorScores(
            double distance, double freshness, double capacity, double confidence, double favourite) {}

    private record Scored(String alias, double score) {}

    private record CategoryScore(ShadowReasonCategory category, double score) {}
}
