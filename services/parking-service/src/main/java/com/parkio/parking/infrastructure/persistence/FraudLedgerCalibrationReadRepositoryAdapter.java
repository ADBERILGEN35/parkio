package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.calibration.FraudLedgerCalibrationCandidate;
import com.parkio.parking.application.port.FraudLedgerCalibrationReadPort;
import com.parkio.parking.calibration.CalibrationMappingVersion;
import com.parkio.parking.calibration.CalibrationObservationHorizon;
import com.parkio.parking.calibration.CalibrationPolicyConfig;
import com.parkio.parking.fraud.FraudConfidenceBand;
import com.parkio.parking.fraud.FraudDisposition;
import com.parkio.parking.fraud.FraudRiskBand;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FraudLedgerCalibrationReadRepositoryAdapter implements FraudLedgerCalibrationReadPort {

    private static final String CALIBRATION_MAPPING_VERSION = CalibrationMappingVersion.V1.value();
    private static final String CALIBRATION_POLICY_VERSION = CalibrationPolicyConfig.POLICY_VERSION;
    private static final String FRAUD_HORIZON = CalibrationObservationHorizon.SHORT_TERM.name();

    private final NamedParameterJdbcTemplate jdbc;

    public FraudLedgerCalibrationReadRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<FraudLedgerCalibrationCandidate> findUnobservedFraudEvaluations(int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("calibrationMappingVersion", CALIBRATION_MAPPING_VERSION)
                .addValue("calibrationPolicyVersion", CALIBRATION_POLICY_VERSION)
                .addValue("fraudHorizon", FRAUD_HORIZON);

        return jdbc.query(
                """
                SELECT
                    fel.evaluation_id,
                    oh.id AS source_outcome_id,
                    fel.policy_version AS fraud_policy_version,
                    fel.risk_band,
                    fel.confidence_band,
                    fel.disposition,
                    fel.effective_evidence_count,
                    oh.classification AS outcome_classification,
                    fel.evaluated_at
                FROM fraud_evaluation_ledger fel
                JOIN outcome_history oh ON oh.id = fel.source_outcome_record_id
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM calibration_observation co
                    WHERE co.engine_type = 'FRAUD'
                      AND co.source_evaluation_id = fel.evaluation_id
                      AND co.label_source_id = oh.id
                      AND co.observation_horizon = :fraudHorizon
                      AND co.calibration_mapping_version = :calibrationMappingVersion
                      AND co.calibration_policy_version = :calibrationPolicyVersion
                )
                ORDER BY fel.evaluated_at ASC, fel.id ASC
                LIMIT :limit
                """,
                params,
                (rs, rowNum) -> mapFraudCandidate(rs));
    }

    private static FraudLedgerCalibrationCandidate mapFraudCandidate(ResultSet rs) throws SQLException {
        return new FraudLedgerCalibrationCandidate(
                rs.getObject("evaluation_id", UUID.class),
                rs.getObject("source_outcome_id", UUID.class),
                rs.getString("fraud_policy_version"),
                FraudRiskBand.valueOf(rs.getString("risk_band")),
                FraudConfidenceBand.valueOf(rs.getString("confidence_band")),
                FraudDisposition.valueOf(rs.getString("disposition")),
                rs.getInt("effective_evidence_count"),
                rs.getString("outcome_classification"),
                instant(rs, "evaluated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}