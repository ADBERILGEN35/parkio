package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.CalibrationReadinessPort;
import com.parkio.parking.calibration.CalibrationReadinessAssessment;
import com.parkio.parking.infrastructure.persistence.calibration.CalibrationPersistenceMapper;
import com.parkio.parking.infrastructure.persistence.jpa.CalibrationReadinessJpaRepository;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CalibrationReadinessRepositoryAdapter implements CalibrationReadinessPort {

    private final CalibrationReadinessJpaRepository jpa;
    private final CalibrationPersistenceMapper mapper;
    private final JdbcTemplate jdbc;

    public CalibrationReadinessRepositoryAdapter(
            CalibrationReadinessJpaRepository jpa,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.mapper = new CalibrationPersistenceMapper(objectMapper);
        this.jdbc = jdbc;
    }

    @Override
    public void append(CalibrationReadinessAssessment assessment) {
        var entity = mapper.toEntity(assessment, assessment.assessedAt());
        jdbc.update(
                """
                INSERT INTO calibration_readiness_assessment (
                    id,
                    assessment_id,
                    engine_type,
                    policy_version,
                    calibration_report_id,
                    readiness_status,
                    assessed_at,
                    created_at,
                    reason_payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (assessment_id) DO NOTHING
                """,
                entity.getId(),
                entity.getAssessmentId(),
                entity.getEngineType(),
                entity.getPolicyVersion(),
                entity.getCalibrationReportId(),
                entity.getReadinessStatus(),
                Timestamp.from(entity.getAssessedAt()),
                Timestamp.from(entity.getCreatedAt()),
                entity.getReasonPayloadJson());
    }

    @Override
    public Optional<CalibrationReadinessAssessment> findByAssessmentId(UUID assessmentId) {
        return jpa.findByAssessmentId(assessmentId).map(mapper::toReadinessDomain);
    }
}