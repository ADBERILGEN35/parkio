package com.parkio.parking.application.recommendation.ranking.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory rollup store for unit tests. */
final class FakeRankingEvaluationRollupStore implements RankingEvaluationRollupStore {

    private final AtomicReference<Instant> completedThrough = new AtomicReference<>(Instant.EPOCH);
    private final ConcurrentHashMap<Instant, List<RankingEvaluationRollupRecord>> byHour =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Instant> processedSlices = new CopyOnWriteArrayList<>();

    @Override
    public Optional<Instant> findCompletedThrough() {
        return Optional.ofNullable(completedThrough.get());
    }

    @Override
    public void replaceSlice(
            Instant sliceStart,
            Instant sliceEnd,
            List<RankingEvaluationRollupRecord> rows,
            int evaluationsProcessed,
            int outcomesProcessed,
            Instant now) {
        byHour.put(sliceStart, List.copyOf(rows == null ? List.of() : rows));
        processedSlices.add(sliceStart);
        completedThrough.updateAndGet(prev -> prev == null || sliceEnd.isAfter(prev) ? sliceEnd : prev);
    }

    @Override
    public List<RankingEvaluationRollupRecord> listRollupsBetween(
            Instant fromInclusive, Instant toExclusive) {
        List<RankingEvaluationRollupRecord> result = new ArrayList<>();
        for (var entry : byHour.entrySet()) {
            if (!entry.getKey().isBefore(fromInclusive) && entry.getKey().isBefore(toExclusive)) {
                result.addAll(entry.getValue());
            }
        }
        result.sort(Comparator.comparing(RankingEvaluationRollupRecord::rollupHour));
        return List.copyOf(result);
    }

    @Override
    public int deleteRollupsOlderThan(Instant cutoffHourExclusive, int batchSize) {
        List<Instant> doomed = byHour.keySet().stream()
                .filter(h -> h.isBefore(cutoffHourExclusive))
                .sorted()
                .limit(Math.max(1, batchSize))
                .toList();
        for (Instant hour : doomed) {
            byHour.remove(hour);
        }
        return doomed.size();
    }

    @Override
    public int deleteExpiredRawBeforeWatermark(
            Instant expiresBefore, Instant createdBefore, int batchSize) {
        // Raw deletion handled by FakeRankingEvaluationStore in integration-style unit tests.
        return 0;
    }

    List<RankingEvaluationRollupRecord> allRows() {
        List<RankingEvaluationRollupRecord> result = new ArrayList<>();
        byHour.values().forEach(result::addAll);
        return List.copyOf(result);
    }

    int processedSliceCount() {
        return processedSlices.size();
    }
}
