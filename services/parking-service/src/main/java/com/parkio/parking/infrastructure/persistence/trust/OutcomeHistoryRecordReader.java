package com.parkio.parking.infrastructure.persistence.trust;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.infrastructure.persistence.jpa.OutcomeHistoryJpaRepository;
import com.parkio.parking.infrastructure.persistence.outcome.OutcomeHistorySnapshotMapper;
import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Rehydrates durable outcome-history rows for trust shadow reads. */
@Component
public class OutcomeHistoryRecordReader {

    private final OutcomeHistoryJpaRepository jpa;
    private final OutcomeHistorySnapshotMapper mapper;

    public OutcomeHistoryRecordReader(OutcomeHistoryJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.mapper = new OutcomeHistorySnapshotMapper(objectMapper);
    }

    public Optional<OutcomeHistoryRecord> read(UUID recordId) {
        return jpa.findById(recordId).map(mapper::toDomain);
    }
}

