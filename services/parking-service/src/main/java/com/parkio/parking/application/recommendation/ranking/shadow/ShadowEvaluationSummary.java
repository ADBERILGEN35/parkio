package com.parkio.parking.application.recommendation.ranking.shadow;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregate helper over {@link ShadowEvaluationStore} for tests / offline replay.
 * No public HTTP API.
 */
public final class ShadowEvaluationSummary {

    private ShadowEvaluationSummary() {}

    public static Summary summarize(ShadowEvaluationStore store) {
        Objects.requireNonNull(store, "store");
        return summarize(store.snapshot());
    }

    public static Summary summarize(List<ShadowEvaluationRecord> records) {
        Objects.requireNonNull(records, "records");
        Map<ShadowRankingStatus, Integer> byStatus = new EnumMap<>(ShadowRankingStatus.class);
        int success = 0;
        int top1Agreements = 0;
        double spearmanSum = 0.0;
        double meanDeltaSum = 0.0;
        long latencySum = 0L;
        for (ShadowEvaluationRecord record : records) {
            byStatus.merge(record.status(), 1, Integer::sum);
            if (record.status() == ShadowRankingStatus.SUCCESS && record.comparison() != null) {
                success++;
                if (record.comparison().top1Agreement()) {
                    top1Agreements++;
                }
                spearmanSum += record.comparison().spearmanRankCorrelation();
                meanDeltaSum += record.comparison().meanAbsoluteRankDelta();
            }
            latencySum += record.latencyMs();
        }
        double top1Rate = success == 0 ? 0.0 : top1Agreements / (double) success;
        double avgSpearman = success == 0 ? 0.0 : spearmanSum / success;
        double avgMeanDelta = success == 0 ? 0.0 : meanDeltaSum / success;
        double avgLatency = records.isEmpty() ? 0.0 : latencySum / (double) records.size();
        return new Summary(records.size(), Map.copyOf(byStatus), success, top1Rate, avgSpearman, avgMeanDelta, avgLatency);
    }

    public record Summary(
            int total,
            Map<ShadowRankingStatus, Integer> countsByStatus,
            int successCount,
            double top1AgreementRate,
            double averageSpearman,
            double averageMeanRankDelta,
            double averageLatencyMs) {}
}
