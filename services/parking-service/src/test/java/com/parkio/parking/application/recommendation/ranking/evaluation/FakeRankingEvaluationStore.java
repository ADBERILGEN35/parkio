package com.parkio.parking.application.recommendation.ranking.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory {@link RankingEvaluationStore} for unit tests. */
final class FakeRankingEvaluationStore implements RankingEvaluationStore {

    private final Map<UUID, RankingEvaluationSnapshot> snapshots = new ConcurrentHashMap<>();
    private final List<RankingEvaluationOutcomeRecord> outcomes = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failInserts = new AtomicBoolean(false);

    void failNextInserts(boolean fail) {
        failInserts.set(fail);
    }

    List<RankingEvaluationSnapshot> snapshots() {
        return List.copyOf(snapshots.values());
    }

    List<RankingEvaluationOutcomeRecord> outcomes() {
        return List.copyOf(outcomes);
    }

    @Override
    public void insertSnapshot(RankingEvaluationSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (failInserts.get()) {
            throw new IllegalStateException("forced persistence failure");
        }
        if (snapshots.putIfAbsent(snapshot.evaluationId(), snapshot) != null) {
            throw new IllegalStateException("duplicate evaluationId");
        }
    }

    @Override
    public void updateShadowOrder(
            UUID evaluationId,
            List<Integer> shadowOrderByOrdinal,
            Boolean top1Agreement,
            Integer top3Overlap,
            String shadowRankerVersion) {
        RankingEvaluationSnapshot existing = snapshots.get(evaluationId);
        if (existing == null) {
            return;
        }
        snapshots.put(
                evaluationId,
                new RankingEvaluationSnapshot(
                        existing.evaluationId(),
                        existing.createdAt(),
                        existing.expiresAt(),
                        existing.rankingVersion(),
                        existing.rankingStatus(),
                        shadowRankerVersion == null ? existing.shadowRankerVersion() : shadowRankerVersion,
                        existing.featureSchemaVersion(),
                        existing.candidateCount(),
                        existing.inventoryPartial(),
                        existing.inventoryComposition(),
                        existing.deterministicOrderByOrdinal(),
                        shadowOrderByOrdinal,
                        existing.featuresJson(),
                        top1Agreement,
                        top3Overlap));
    }

    @Override
    public Optional<RankingEvaluationSnapshot> findSnapshot(UUID evaluationId) {
        return Optional.ofNullable(snapshots.get(evaluationId));
    }

    @Override
    public boolean insertOutcome(RankingEvaluationOutcomeRecord outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (failInserts.get()) {
            throw new IllegalStateException("forced outcome persistence failure");
        }
        for (RankingEvaluationOutcomeRecord existing : outcomes) {
            if (existing.evaluationId().equals(outcome.evaluationId())
                    && existing.candidateOrdinal() == outcome.candidateOrdinal()
                    && existing.outcomeType() == outcome.outcomeType()) {
                return false;
            }
        }
        outcomes.add(outcome);
        return true;
    }

    @Override
    public List<RankingEvaluationSnapshot> listSnapshotsCreatedBetween(
            Instant fromInclusive, Instant toExclusive) {
        List<RankingEvaluationSnapshot> result = new ArrayList<>();
        for (RankingEvaluationSnapshot snapshot : snapshots.values()) {
            Instant created = snapshot.createdAt();
            if (!created.isBefore(fromInclusive) && created.isBefore(toExclusive)) {
                result.add(snapshot);
            }
        }
        result.sort(Comparator.comparing(RankingEvaluationSnapshot::createdAt));
        return List.copyOf(result);
    }

    @Override
    public List<RankingEvaluationOutcomeRecord> listOutcomesForEvaluations(List<UUID> evaluationIds) {
        if (evaluationIds == null || evaluationIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Boolean> wanted = new LinkedHashMap<>();
        for (UUID id : evaluationIds) {
            wanted.put(id, Boolean.TRUE);
        }
        List<RankingEvaluationOutcomeRecord> result = new ArrayList<>();
        for (RankingEvaluationOutcomeRecord outcome : outcomes) {
            if (wanted.containsKey(outcome.evaluationId())) {
                result.add(outcome);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public int deleteExpiredBefore(Instant cutoff, int batchSize) {
        List<UUID> expired = snapshots.values().stream()
                .filter(s -> !s.expiresAt().isAfter(cutoff))
                .sorted(Comparator.comparing(RankingEvaluationSnapshot::expiresAt))
                .map(RankingEvaluationSnapshot::evaluationId)
                .limit(Math.max(1, batchSize))
                .toList();
        for (UUID id : expired) {
            snapshots.remove(id);
            outcomes.removeIf(o -> o.evaluationId().equals(id));
        }
        return expired.size();
    }
}
