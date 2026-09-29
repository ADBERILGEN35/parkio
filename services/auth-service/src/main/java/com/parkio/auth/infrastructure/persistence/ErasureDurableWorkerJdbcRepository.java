package com.parkio.auth.infrastructure.persistence;

import com.parkio.auth.application.ErasureDurableWorkerClaim;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ErasureDurableWorkerJdbcRepository implements ErasureDurableWorkerRepository {

    private static final String CLAIM_SQL = """
            WITH picked AS (
                SELECT id
                FROM erasure_requests
                WHERE status NOT IN ('COMPLETE', 'FAILED_RETRYING')
                  AND durable_recording_status IN ('PENDING_DURABLE', 'DURABLY_RECORDED')
                  AND durable_retry_attempt_count < ?
                  AND (durable_retry_next_at IS NULL OR durable_retry_next_at <= ?)
                  AND (
                        durable_worker_claim_token IS NULL
                        OR durable_worker_claim_expires_at IS NULL
                        OR durable_worker_claim_expires_at <= ?
                      )
                ORDER BY COALESCE(durable_retry_next_at, requested_at), requested_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE erasure_requests r
            SET durable_worker_claim_token = ?,
                durable_worker_claim_expires_at = ?
            FROM picked
            WHERE r.id = picked.id
            RETURNING r.id, r.auth_user_id, r.requested_at, r.status, r.durable_recording_status,
                      r.durable_worker_claim_token, r.durable_worker_claim_expires_at
            """;

    private final JdbcTemplate jdbc;

    public ErasureDurableWorkerJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public List<ErasureDurableWorkerClaim> claimBatch(
            Instant now, int limit, int maxAttempts, Instant claimExpiresAt, UUID claimToken) {
        return jdbc.query(
                CLAIM_SQL,
                this::mapClaim,
                maxAttempts,
                Timestamp.from(now),
                Timestamp.from(now),
                limit,
                claimToken,
                Timestamp.from(claimExpiresAt));
    }

    @Override
    public int scheduleInitialPersistRetry(UUID requestId, Instant nextAttemptAt, String errorCode) {
        return jdbc.update(
                """
                UPDATE erasure_requests
                SET durable_retry_attempt_count = GREATEST(durable_retry_attempt_count, 1),
                    durable_retry_next_at = ?,
                    last_error_code = ?
                WHERE id = ?
                  AND durable_recording_status = 'PENDING_DURABLE'
                  AND status NOT IN ('COMPLETE', 'FAILED_RETRYING')
                """,
                Timestamp.from(nextAttemptAt),
                errorCode,
                requestId);
    }

    @Override
    public int releaseClaim(UUID requestId, UUID claimToken, Instant asOf) {
        return jdbc.update(
                """
                UPDATE erasure_requests
                SET durable_worker_claim_token = NULL,
                    durable_worker_claim_expires_at = NULL
                WHERE id = ?
                  AND durable_worker_claim_token = ?
                  AND durable_worker_claim_expires_at > ?
                """,
                requestId,
                claimToken,
                Timestamp.from(asOf));
    }

    @Override
    public int recordRetryScheduled(
            UUID requestId,
            UUID claimToken,
            int newAttemptCount,
            Instant nextAttemptAt,
            String errorCode) {
        return jdbc.update(
                """
                UPDATE erasure_requests
                SET durable_retry_attempt_count = ?,
                    durable_retry_next_at = ?,
                    last_error_code = ?,
                    durable_worker_claim_token = NULL,
                    durable_worker_claim_expires_at = NULL
                WHERE id = ?
                  AND durable_worker_claim_token = ?
                """,
                newAttemptCount,
                Timestamp.from(nextAttemptAt),
                errorCode,
                requestId,
                claimToken);
    }

    @Override
    public int markDurablyRecordedIfClaimed(UUID requestId, UUID claimToken, Instant asOf) {
        return jdbc.update(
                """
                UPDATE erasure_requests
                SET durable_recording_status = 'DURABLY_RECORDED',
                    durable_worker_claim_token = NULL,
                    durable_worker_claim_expires_at = NULL,
                    last_error_code = NULL
                WHERE id = ?
                  AND durable_recording_status = 'PENDING_DURABLE'
                  AND durable_worker_claim_token = ?
                  AND durable_worker_claim_expires_at > ?
                """,
                requestId,
                claimToken,
                Timestamp.from(asOf));
    }

    private ErasureDurableWorkerClaim mapClaim(ResultSet rs, int rowNum) throws SQLException {
        return new ErasureDurableWorkerClaim(
                rs.getObject("id", UUID.class),
                rs.getObject("auth_user_id", UUID.class),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getString("status"),
                rs.getString("durable_recording_status"),
                rs.getObject("durable_worker_claim_token", UUID.class),
                rs.getTimestamp("durable_worker_claim_expires_at").toInstant());
    }
}
