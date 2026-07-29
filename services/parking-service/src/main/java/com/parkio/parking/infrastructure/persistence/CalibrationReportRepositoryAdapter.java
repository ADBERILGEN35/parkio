package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.DuplicateCalibrationReportException;
import com.parkio.parking.application.port.CalibrationReportPort;
import com.parkio.parking.calibration.CalibrationEngineType;
import com.parkio.parking.calibration.CalibrationReport;
import com.parkio.parking.infrastructure.persistence.calibration.CalibrationPersistenceMapper;
import com.parkio.parking.infrastructure.persistence.jpa.CalibrationReportJpaRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CalibrationReportRepositoryAdapter implements CalibrationReportPort {

    private final CalibrationReportJpaRepository jpa;
    private final CalibrationPersistenceMapper mapper;
    private final JdbcTemplate jdbc;

    public CalibrationReportRepositoryAdapter(
            CalibrationReportJpaRepository jpa,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.mapper = new CalibrationPersistenceMapper(objectMapper);
        this.jdbc = jdbc;
    }

    @Override
    public void append(CalibrationReport report) {
        Instant createdAt = report.generatedAt();
        var entity = mapper.toEntity(report, createdAt);
        try {
            jdbc.update(
                    """
                    INSERT INTO calibration_report (
                        id,
                        report_id,
                        engine_type,
                        baseline_policy_version,
                        candidate_policy_version,
                        calibration_policy_version,
                        window_start,
                        window_end,
                        cohort_key,
                        observation_count,
                        labeled_count,
                        report_status,
                        source_watermark,
                        generated_at,
                        created_at,
                        report_payload_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    entity.getId(),
                    entity.getReportId(),
                    entity.getEngineType(),
                    entity.getBaselinePolicyVersion(),
                    entity.getCandidatePolicyVersion(),
                    entity.getCalibrationPolicyVersion(),
                    Timestamp.from(entity.getWindowStart()),
                    Timestamp.from(entity.getWindowEnd()),
                    entity.getCohortKey(),
                    entity.getObservationCount(),
                    entity.getLabeledCount(),
                    entity.getReportStatus(),
                    Timestamp.from(entity.getSourceWatermark()),
                    Timestamp.from(entity.getGeneratedAt()),
                    Timestamp.from(entity.getCreatedAt()),
                    entity.getReportPayloadJson());
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateCalibrationReportException(
                    "Calibration report already exists for logical key engine="
                            + report.engineType()
                            + " windowEnd="
                            + report.window().end()
                            + " cohort="
                            + report.cohortKey().canonicalKey());
        }
    }

    @Override
    public Optional<CalibrationReport> findByReportId(UUID reportId) {
        return jpa.findByReportId(reportId).map(mapper::toReportDomain);
    }

    @Override
    public Optional<CalibrationReport> findLatestByEngineAndPolicy(
            CalibrationEngineType engineType, String policyVersion) {
        return jpa.findFirstByEngineTypeAndBaselinePolicyVersionOrderByGeneratedAtDescIdDesc(
                        engineType.name(), policyVersion)
                .map(mapper::toReportDomain);
    }
}