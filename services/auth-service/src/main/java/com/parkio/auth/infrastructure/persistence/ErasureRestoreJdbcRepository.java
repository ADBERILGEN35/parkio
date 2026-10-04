package com.parkio.auth.infrastructure.persistence;

import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.port.ErasureRestoreRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to the V26 restore replay tables (PostgreSQL). */
@Repository
public class ErasureRestoreJdbcRepository implements ErasureRestoreRepository {

    private final JdbcTemplate jdbc;

    public ErasureRestoreJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RestoreAttempt> findAttempt(UUID recoveryAttemptId) {
        return jdbc.query("""
                        SELECT recovery_attempt_id, restored_dataset_id, erasure_set_digest, user_count, started_at
                        FROM erasure_restore_attempts WHERE recovery_attempt_id = ?
                        """,
                (rs, row) -> new RestoreAttempt(rs.getObject("recovery_attempt_id", UUID.class),
                        rs.getString("restored_dataset_id"), rs.getString("erasure_set_digest"),
                        rs.getInt("user_count"), rs.getTimestamp("started_at").toInstant()),
                recoveryAttemptId).stream().findFirst();
    }

    @Override
    public void insertAttempt(RestoreAttempt attempt, List<ErasureLedgerEntry> users) {
        jdbc.update("""
                        INSERT INTO erasure_restore_attempts
                            (recovery_attempt_id, restored_dataset_id, erasure_set_digest, user_count, started_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                attempt.recoveryAttemptId(), attempt.restoredDatasetId(), attempt.erasureSetDigest(),
                attempt.userCount(), Timestamp.from(attempt.startedAt()));
        jdbc.batchUpdate("""
                        INSERT INTO erasure_restore_attempt_users (recovery_attempt_id, auth_user_id, erased_at)
                        VALUES (?, ?, ?)
                        """,
                users, 500, (ps, user) -> {
                    ps.setObject(1, attempt.recoveryAttemptId());
                    ps.setObject(2, user.authUserId());
                    ps.setTimestamp(3, Timestamp.from(user.erasedAt()));
                });
    }

    @Override
    public boolean isAttemptUser(UUID recoveryAttemptId, UUID authUserId) {
        Integer found = jdbc.queryForObject("""
                        SELECT count(*) FROM erasure_restore_attempt_users
                        WHERE recovery_attempt_id = ? AND auth_user_id = ?
                        """,
                Integer.class, recoveryAttemptId, authUserId);
        return found != null && found > 0;
    }

    @Override
    public void upsertAck(UUID recoveryAttemptId, UUID authUserId, String serviceName, String status, Instant ackedAt) {
        jdbc.update("""
                        INSERT INTO erasure_restore_acks (recovery_attempt_id, auth_user_id, service_name, status, acked_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (recovery_attempt_id, auth_user_id, service_name)
                        DO UPDATE SET status = EXCLUDED.status, acked_at = EXCLUDED.acked_at
                        """,
                recoveryAttemptId, authUserId, serviceName, status, Timestamp.from(ackedAt));
    }

    @Override
    public Map<String, Long> countAcksByService(UUID recoveryAttemptId, String status) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("""
                        SELECT service_name, count(*) AS acks FROM erasure_restore_acks
                        WHERE recovery_attempt_id = ? AND status = ?
                        GROUP BY service_name ORDER BY service_name
                        """,
                rs -> {
                    counts.put(rs.getString("service_name"), rs.getLong("acks"));
                },
                recoveryAttemptId, status);
        return counts;
    }
}
