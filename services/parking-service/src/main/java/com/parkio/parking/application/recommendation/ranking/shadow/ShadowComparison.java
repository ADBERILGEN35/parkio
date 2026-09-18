package com.parkio.parking.application.recommendation.ranking.shadow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agreement metrics between authoritative order and shadow order. Safe for n=1
 * (Spearman = 1.0). Never returns NaN.
 */
public record ShadowComparison(
        boolean top1Agreement,
        int top3Overlap,
        double spearmanRankCorrelation,
        double meanAbsoluteRankDelta,
        int maxRankDelta) {

    public ShadowComparison {
        if (top3Overlap < 0 || top3Overlap > 3) {
            throw new IllegalArgumentException("top3Overlap must be in [0,3]");
        }
        if (!Double.isFinite(spearmanRankCorrelation)) {
            throw new IllegalArgumentException("spearmanRankCorrelation must be finite");
        }
        if (!Double.isFinite(meanAbsoluteRankDelta) || meanAbsoluteRankDelta < 0.0) {
            throw new IllegalArgumentException("meanAbsoluteRankDelta must be finite and non-negative");
        }
        if (maxRankDelta < 0) {
            throw new IllegalArgumentException("maxRankDelta must be non-negative");
        }
    }

    /**
     * @param authoritativeAliases aliases in authoritative order (c0.. at positions)
     * @param shadowAliases aliases in shadow order
     */
    public static ShadowComparison compare(List<String> authoritativeAliases, List<String> shadowAliases) {
        Objects.requireNonNull(authoritativeAliases, "authoritativeAliases");
        Objects.requireNonNull(shadowAliases, "shadowAliases");
        int n = authoritativeAliases.size();
        if (n == 0) {
            return new ShadowComparison(true, 0, 1.0, 0.0, 0);
        }
        if (n != shadowAliases.size()) {
            throw new IllegalArgumentException("alias lists must be the same length");
        }

        Map<String, Integer> authPos = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            authPos.put(authoritativeAliases.get(i), i);
        }
        Map<String, Integer> shadowPos = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            String alias = shadowAliases.get(i);
            if (!authPos.containsKey(alias)) {
                throw new IllegalArgumentException("unknown shadow alias: " + alias);
            }
            shadowPos.put(alias, i);
        }

        boolean top1 = Objects.equals(authoritativeAliases.getFirst(), shadowAliases.getFirst());
        int overlap = topKOverlap(authoritativeAliases, shadowAliases, 3);

        long absDeltaSum = 0L;
        int maxDelta = 0;
        double sumD2 = 0.0;
        for (String alias : authoritativeAliases) {
            int a = authPos.get(alias);
            int s = shadowPos.get(alias);
            int delta = Math.abs(a - s);
            absDeltaSum += delta;
            maxDelta = Math.max(maxDelta, delta);
            sumD2 += (double) delta * (double) delta;
        }

        double meanDelta = absDeltaSum / (double) n;
        double spearman;
        if (n == 1) {
            spearman = 1.0;
        } else {
            spearman = 1.0 - (6.0 * sumD2) / (n * ((double) n * n - 1.0));
            if (!Double.isFinite(spearman)) {
                spearman = 0.0;
            }
            spearman = Math.max(-1.0, Math.min(1.0, spearman));
        }
        return new ShadowComparison(top1, overlap, spearman, meanDelta, maxDelta);
    }

    static int topKOverlap(List<String> a, List<String> b, int k) {
        int limit = Math.min(k, Math.min(a.size(), b.size()));
        if (limit <= 0) {
            return 0;
        }
        java.util.HashSet<String> topA = new java.util.HashSet<>(limit);
        for (int i = 0; i < limit; i++) {
            topA.add(a.get(i));
        }
        int overlap = 0;
        for (int i = 0; i < limit; i++) {
            if (topA.contains(b.get(i))) {
                overlap++;
            }
        }
        return Math.min(overlap, 3);
    }
}
