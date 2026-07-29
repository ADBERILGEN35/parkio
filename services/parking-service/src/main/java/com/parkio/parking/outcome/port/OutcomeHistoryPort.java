package com.parkio.parking.outcome.port;

import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only durable outcome validation history boundary. */
public interface OutcomeHistoryPort {

    void append(OutcomeHistoryRecord record);

    Optional<OutcomeHistoryRecord> findLatest(UUID parkingSpotId);

    Optional<OutcomeHistoryRecord> findLatestAtOrBefore(UUID parkingSpotId, Instant cutoffInclusive);

    Optional<OutcomeHistoryRecord> findByEvaluationId(UUID evaluationId);

    List<OutcomeHistoryRecord> findAll(UUID parkingSpotId);

    static OutcomeHistoryPort noop() {
        return new OutcomeHistoryPort() {
            @Override
            public void append(OutcomeHistoryRecord record) {}

            @Override
            public Optional<OutcomeHistoryRecord> findLatest(UUID parkingSpotId) {
                return Optional.empty();
            }

            @Override
            public Optional<OutcomeHistoryRecord> findLatestAtOrBefore(UUID parkingSpotId, Instant cutoffInclusive) {
                return Optional.empty();
            }

            @Override
            public Optional<OutcomeHistoryRecord> findByEvaluationId(UUID evaluationId) {
                return Optional.empty();
            }

            @Override
            public List<OutcomeHistoryRecord> findAll(UUID parkingSpotId) {
                return List.of();
            }
        };
    }
}