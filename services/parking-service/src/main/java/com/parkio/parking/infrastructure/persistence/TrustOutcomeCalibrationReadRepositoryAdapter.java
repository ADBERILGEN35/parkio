package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.calibration.TrustOutcomeCalibrationPair;
import com.parkio.parking.application.port.TrustOutcomeCalibrationReadPort;
import com.parkio.parking.calibration.CalibrationMappingVersion;
import com.parkio.parking.calibration.CalibrationObservationHorizon;
import com.parkio.parking.calibration.CalibrationPolicyConfig;
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
public class TrustOutcomeCalibrationReadRepositoryAdapter implements TrustOutcomeCalibrationReadPort {

    private static final String CALIBRATION_MAPPING_VERSION = CalibrationMappingVersion.V1.value();
    private static final String CALIBRATION_POLICY_VERSION = CalibrationPolicyConfig.POLICY_VERSION;
    private static final String TRUST_HORIZON = CalibrationObservationHorizon.MEDIUM_TERM.name();

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TrustOutcomeCalibrationReadRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public List<TrustOutcomeCalibrationPair> findUnobservedTrustOutcomePairs(int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("calibrationMappingVersion", CALIBRATION_MAPPING_VERSION)
                .addValue("calibrationPolicyVersion", CALIBRATION_POLICY_VERSION)
                .addValue("trustHorizon", TRUST_HORIZON);

        return jdbc.query(
                """
                SELECT
                    tl.evaluation_id AS trust_evaluation_id,
                    oh.id AS source_outcome_id,
                    tl.trust_policy_version,
                    tl.trust_level,
                    tl.evaluation_json,
                    oh.classification,
                    oh.primary_reason,
                    tl.attribution_quality,
                    tl.evaluated_at,
                    oh.evaluated_at AS labeled_at
                FROM trust_ledger tl
                JOIN outcome_history oh ON oh.id = tl.source_outcome_record_id
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM calibration_observation co
                    WHERE co.engine_type = 'TRUST'
                      AND co.source_evaluation_id = tl.evaluation_id
                      AND co.label_source_id = oh.id
                      AND co.observation_horizon = :trustHorizon
                      AND co.calibration_mapping_version = :calibrationMappingVersion
                      AND co.calibration_policy_version = :calibrationPolicyVersion
                )
                ORDER BY tl.evaluated_at ASC, tl.id ASC
                LIMIT :limit
                """,
                params,
                (rs, rowNum) -> mapTrustPair(rs));
    }

    private TrustOutcomeCalibrationPair mapTrustPair(ResultSet rs) throws SQLException {
        return new TrustOutcomeCalibrationPair(
                rs.getObject("trust_evaluation_id", UUID.class),
                rs.getObject("source_outcome_id", UUID.class),
                rs.getString("trust_policy_version"),
                rs.getString("trust_level"),
                trustConfidenceBand(rs.getString("evaluation_json")),
                rs.getString("classification"),
                rs.getString("primary_reason"),
                rs.getString("attribution_quality"),
                instant(rs, "evaluated_at"),
                instant(rs, "labeled_at"));
    }

    private String trustConfidenceBand(String evaluationJson) {
        try {
            JsonNode root = objectMapper.readTree(evaluationJson);
            int basisPoints = root.path("resultingSnapshot").path("confidenceBasisPoints").asInt(0);
            return confidenceBand(basisPoints);
        } catch (Exception ex) {
            return "UNKNOWN";
        }
    }

    private static String confidenceBand(int basisPoints) {
        if (basisPoints < 3_334) {
            return "LOW";
        }
        if (basisPoints < 6_667) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}