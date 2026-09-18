package com.parkio.parking.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface LinkCandidateGenerationRunPort {
    record StartRequest(
            String sourceFamilyPair,
            String algorithmVersion,
            boolean dryRun,
            boolean persistCandidates,
            double maxDistanceMeters,
            int leftRecordLimit,
            int pairLimit,
            int sampleLimit,
            String leftScopeJson,
            String operatorUserId,
            String correlationId,
            Instant startedAt) {}

    record Aggregates(
            int leftRecordsConsidered,
            int pairsConsidered,
            int candidatesEligible,
            int candidatesPersisted,
            int hardConflicts,
            Map<String, Integer> skips,
            int duplicatesSuppressed,
            int failures) {}

    record RunRecord(
            UUID id,
            String sourceFamilyPair,
            String algorithmVersion,
            boolean dryRun,
            boolean persistCandidates,
            double maxDistanceMeters,
            int leftRecordLimit,
            int pairLimit,
            int sampleLimit,
            String leftScopeJson,
            String status,
            Aggregates aggregates,
            String samplesJson,
            String failureCategory,
            String operatorUserId,
            String correlationId,
            Instant startedAt,
            Instant completedAt,
            Long durationMs) {}

    record RunPage(List<RunRecord> content, int page, int size, long totalElements) {}

    Optional<UUID> tryStart(StartRequest request);

    void complete(
            UUID runId,
            String status,
            Aggregates aggregates,
            String samplesJson,
            String failureCategory,
            Instant completedAt,
            long durationMs);

    Optional<RunRecord> findById(UUID id);

    RunPage findPage(int page, int size, String sourceFamilyPair);

    int countActiveRunning();

    Optional<RunRecord> findLatestCompleted();

    int countStaleRunning(Instant olderThan);
}
