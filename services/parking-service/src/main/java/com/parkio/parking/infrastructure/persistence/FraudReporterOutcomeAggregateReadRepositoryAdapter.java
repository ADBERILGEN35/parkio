package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.fraud.FraudReporterOutcomeAggregate;
import com.parkio.parking.application.port.FraudReporterOutcomeAggregateReadPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FraudReporterOutcomeAggregateReadRepositoryAdapter implements FraudReporterOutcomeAggregateReadPort {

    private final NamedParameterJdbcTemplate jdbc;

    public FraudReporterOutcomeAggregateReadRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public FraudReporterOutcomeAggregate aggregateReporterContributions(
            UUID reporterUserId,
            Instant windowEndInclusive,
            Instant windowStartInclusive,
            UUID watermarkOutcomeRecordId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("reporterUserId", reporterUserId)
                .addValue("windowStart", Timestamp.from(windowStartInclusive))
                .addValue("windowEnd", Timestamp.from(windowEndInclusive))
                .addValue("watermarkOutcomeRecordId", watermarkOutcomeRecordId);

        Map<String, Object> counts = jdbc.queryForMap(
                """
                SELECT
                    COUNT(*) AS eligible_contribution_count,
                    COUNT(*) FILTER (
                        WHERE oh.classification = 'CONFIRMED_INCORRECT'
                          AND oh.primary_reason IN ('NEGATIVE_VERIFICATION', 'MODERATOR_REJECTION', 'AI_REJECTION', 'REVIEW_FAILED')
                    ) AS direct_confirmed_incorrect_count,
                    COUNT(*) FILTER (WHERE oh.classification = 'LIKELY_INCORRECT') AS likely_incorrect_count,
                    COUNT(*) FILTER (WHERE oh.classification IN ('CONFIRMED_CORRECT', 'LIKELY_CORRECT')) AS confirmed_correct_count,
                    COUNT(*) FILTER (WHERE oh.classification = 'UNKNOWN') AS unknown_count,
                    COUNT(*) FILTER (WHERE oh.classification = 'EXPIRED_WITHOUT_EVIDENCE') AS expired_without_evidence_count
                FROM outcome_history oh
                JOIN parking_spots ps ON ps.id = oh.parking_spot_id
                WHERE ps.owner_user_id = :reporterUserId
                  AND oh.evaluated_at >= :windowStart
                  AND oh.evaluated_at <= :windowEnd
                """,
                params);

        Instant watermarkEvaluatedAt = jdbc.queryForObject(
                """
                SELECT evaluated_at
                FROM outcome_history
                WHERE id = :watermarkOutcomeRecordId
                """,
                params,
                Instant.class);

        return new FraudReporterOutcomeAggregate(
                reporterUserId,
                windowStartInclusive,
                windowEndInclusive,
                watermarkOutcomeRecordId,
                watermarkEvaluatedAt,
                intValue(counts.get("eligible_contribution_count")),
                intValue(counts.get("direct_confirmed_incorrect_count")),
                intValue(counts.get("likely_incorrect_count")),
                intValue(counts.get("confirmed_correct_count")),
                intValue(counts.get("unknown_count")),
                intValue(counts.get("expired_without_evidence_count")));
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
