package com.parkio.parking.outcome.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;
import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.outcome.OutcomeSnapshot;
import com.parkio.parking.outcome.confidence.OutcomeConfidence;
import com.parkio.parking.outcome.evaluation.OutcomeEvaluationContext;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;
import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import com.parkio.parking.outcome.history.OutcomeSnapshotSchemaVersion;
import com.parkio.parking.outcome.policy.OutcomePolicyConfig;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutcomeHistoryPortTest {

    @Test
    void inMemoryPortTracksSnapshots() {
        OutcomeHistoryPort port = new InMemoryOutcomeHistoryPort();
        UUID spotId = UUID.randomUUID();
        OutcomeHistoryRecord first = record(spotId, OutcomeClassification.UNKNOWN, Instant.parse("2026-07-28T10:00:00Z"));
        OutcomeHistoryRecord second = record(spotId, OutcomeClassification.CONFIRMED_CORRECT, Instant.parse("2026-07-28T10:01:00Z"));
        port.append(first);
        port.append(second);
        assertThat(port.findLatest(spotId)).contains(second);
        assertThat(port.findAll(spotId)).hasSize(2);
        assertThat(OutcomeHistoryPort.noop().findLatest(spotId)).isEmpty();
    }

    private static OutcomeHistoryRecord record(UUID spotId, OutcomeClassification classification, Instant at) {
        OutcomeEvidence evidence = new OutcomeEvidence(
                spotId,
                ParkingSpotStatus.VERIFIED,
                at,
                at,
                at.plus(Duration.ofMinutes(10)),
                at,
                1,
                0,
                1.0,
                OutcomeTimeline.of(at, at.plus(Duration.ofMinutes(10)), List.of()));
        OutcomeEvaluationContext context = new OutcomeEvaluationContext(
                at, OutcomePolicyConfig.POLICY_VERSION, Duration.ofMinutes(10));
        OutcomeEvaluation evaluation = new OutcomeEvaluation(
                spotId,
                classification,
                OutcomeConfidence.of(80),
                OutcomeReason.SINGLE_AVAILABLE_VERIFICATION,
                Set.of(OutcomeReason.SINGLE_AVAILABLE_VERIFICATION),
                evidence.timeline(),
                Duration.ofMinutes(1),
                true,
                OutcomePolicyConfig.POLICY_VERSION,
                at);
        OutcomeSnapshot snapshot = new OutcomeSnapshot(evidence, context, evaluation);
        UUID evaluationId = UUID.nameUUIDFromBytes(("test|" + spotId + '|' + at).getBytes(StandardCharsets.UTF_8));
        return new OutcomeHistoryRecord(
                UUID.nameUUIDFromBytes(("record|" + evaluationId).getBytes(StandardCharsets.UTF_8)),
                evaluationId,
                spotId,
                OutcomePolicyConfig.POLICY_VERSION,
                OutcomeSnapshotSchemaVersion.V1,
                OutcomeEvaluationTrigger.PUBLICATION,
                UUID.nameUUIDFromBytes(("trigger|" + evaluationId).getBytes(StandardCharsets.UTF_8)),
                at,
                at,
                snapshot,
                classification,
                OutcomeConfidence.of(80),
                OutcomeReason.SINGLE_AVAILABLE_VERIFICATION,
                true,
                at);
    }

    private static final class InMemoryOutcomeHistoryPort implements OutcomeHistoryPort {
        private final List<OutcomeHistoryRecord> records = new ArrayList<>();

        @Override
        public void append(OutcomeHistoryRecord record) {
            records.add(record);
        }

        @Override
        public java.util.Optional<OutcomeHistoryRecord> findLatest(UUID parkingSpotId) {
            return records.stream()
                    .filter(record -> record.parkingSpotId().equals(parkingSpotId))
                    .reduce((first, second) -> second);
        }

        @Override
        public java.util.Optional<OutcomeHistoryRecord> findLatestAtOrBefore(UUID parkingSpotId, Instant cutoffInclusive) {
            return records.stream()
                    .filter(record -> record.parkingSpotId().equals(parkingSpotId))
                    .filter(record -> !record.evaluatedAt().isAfter(cutoffInclusive))
                    .reduce((first, second) -> second);
        }

        @Override
        public java.util.Optional<OutcomeHistoryRecord> findByEvaluationId(UUID evaluationId) {
            return records.stream().filter(record -> record.evaluationId().equals(evaluationId)).findFirst();
        }

        @Override
        public List<OutcomeHistoryRecord> findAll(UUID parkingSpotId) {
            return records.stream()
                    .filter(record -> record.parkingSpotId().equals(parkingSpotId))
                    .toList();
        }
    }
}