package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.DuplicateOutcomeHistoryException;
import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import com.parkio.parking.outcome.port.OutcomeHistoryPort;
import com.parkio.parking.infrastructure.persistence.jpa.OutcomeHistoryJpaRepository;
import com.parkio.parking.infrastructure.persistence.outcome.OutcomeHistorySnapshotMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class OutcomeHistoryRepositoryAdapter implements OutcomeHistoryPort {

    private final OutcomeHistoryJpaRepository jpa;
    private final OutcomeHistorySnapshotMapper mapper;

    public OutcomeHistoryRepositoryAdapter(OutcomeHistoryJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.mapper = new OutcomeHistorySnapshotMapper(objectMapper);
    }

    @Override
    public void append(OutcomeHistoryRecord record) {
        try {
            jpa.save(mapper.toEntity(record));
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateOutcomeHistoryException("Outcome history already exists for evaluation " + record.evaluationId());
        }
    }

    @Override
    public Optional<OutcomeHistoryRecord> findLatest(UUID parkingSpotId) {
        return jpa.findTopByParkingSpotIdOrderByEvaluatedAtDescIdDesc(parkingSpotId).map(mapper::toDomain);
    }

    @Override
    public Optional<OutcomeHistoryRecord> findLatestAtOrBefore(UUID parkingSpotId, Instant cutoffInclusive) {
        return jpa.findTopByParkingSpotIdAndEvaluatedAtLessThanEqualOrderByEvaluatedAtDescIdDesc(parkingSpotId, cutoffInclusive)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<OutcomeHistoryRecord> findByEvaluationId(UUID evaluationId) {
        return jpa.findByEvaluationId(evaluationId).map(mapper::toDomain);
    }

    @Override
    public List<OutcomeHistoryRecord> findAll(UUID parkingSpotId) {
        return jpa.findByParkingSpotIdOrderByEvaluatedAtAscIdAsc(parkingSpotId).stream().map(mapper::toDomain).toList();
    }
}