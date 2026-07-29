package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.DuplicateCalibrationObservationException;
import com.parkio.parking.application.port.CalibrationObservationPort;
import com.parkio.parking.calibration.CalibrationEngineType;
import com.parkio.parking.calibration.CalibrationObservation;
import com.parkio.parking.infrastructure.persistence.calibration.CalibrationPersistenceMapper;
import com.parkio.parking.infrastructure.persistence.jpa.CalibrationObservationJpaRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CalibrationObservationRepositoryAdapter implements CalibrationObservationPort {

    private final CalibrationObservationJpaRepository jpa;
    private final CalibrationPersistenceMapper mapper;
    private final JdbcTemplate jdbc;

    public CalibrationObservationRepositoryAdapter(
            CalibrationObservationJpaRepository jpa,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.mapper = new CalibrationPersistenceMapper(objectMapper);
        this.jdbc = jdbc;
    }

    @Override
    public void append(CalibrationObservation observation) {
        var entity = mapper.toEntity(observation);
        try {
            jdbc.update(
                    """
                    INSERT INTO calibration_observation (
                        id,
                        observation_id,
                        engine_type,
                        source_evaluation_id,
                        label_source_id,
                        policy_version,
                        schema_version,
                        mapping_version,
                        aggregation_version,
                        calibration_mapping_version,
                        calibration_policy_version,
                        observation_horizon,
                        cohort_key,
                        attribution_quality,
                        label_quality,
                        label_finality,
                        predicted_at,
                        labeled_at,
                        created_at,
                        observation_payload_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    entity.getId(),
                    entity.getObservationId(),
                    entity.getEngineType(),
                    entity.getSourceEvaluationId(),
                    entity.getLabelSourceId(),
                    entity.getPolicyVersion(),
                    entity.getSchemaVersion(),
                    entity.getMappingVersion(),
                    entity.getAggregationVersion(),
                    entity.getCalibrationMappingVersion(),
                    entity.getCalibrationPolicyVersion(),
                    entity.getObservationHorizon(),
                    entity.getCohortKey(),
                    entity.getAttributionQuality(),
                    entity.getLabelQuality(),
                    entity.getLabelFinality(),
                    Timestamp.from(entity.getPredictedAt()),
                    Timestamp.from(entity.getLabeledAt()),
                    Timestamp.from(entity.getCreatedAt()),
                    entity.getObservationPayloadJson());
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateCalibrationObservationException(
                    "Calibration observation already exists for logical key engine="
                            + observation.engineType()
                            + " evaluation="
                            + observation.prediction().sourceEvaluationId()
                            + " label="
                            + observation.label().sourceRecordId());
        }
    }

    @Override
    public Optional<CalibrationObservation> findByObservationId(UUID observationId) {
        return jpa.findByObservationId(observationId).map(mapper::toObservationDomain);
    }

    @Override
    public List<CalibrationObservation> findByEngineAndWindow(
            CalibrationEngineType engineType, Instant windowStart, Instant windowEnd) {
        return jpa.findByEngineTypeAndPredictedAtBetweenOrderByPredictedAtAscIdAsc(
                        engineType.name(), windowStart, windowEnd)
                .stream()
                .map(mapper::toObservationDomain)
                .toList();
    }
}