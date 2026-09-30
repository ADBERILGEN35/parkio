package com.parkio.media.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Durable state of media account erasure (V14): one {@code media_erasure_jobs} row per consumed
 * erase request event, plus {@code media_files.object_deleted_at} per confirmed stored object.
 * Every method is a single statement, so outside a caller's transaction it commits on its own.
 */
@Component
public class MediaErasureJobStore {

    public static final String PENDING = "PENDING_OBJECTS";
    public static final String ACK_QUEUED = "ACK_QUEUED";

    private static final int MAX_ERROR_LENGTH = 512;

    private final JdbcTemplate jdbc;

    public MediaErasureJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A job; {@code ackEventId} is the deterministic ACK event id of the shared contract. */
    public record Job(UUID ackEventId, UUID erasureRequestId, UUID authUserId, String status, int attempts) {

        public boolean pending() {
            return PENDING.equals(status);
        }
    }

    /** A soft-deleted media row whose stored object is not yet confirmed deleted. */
    public record PendingObject(UUID mediaId, String objectKey) {
    }

    /**
     * Opens the job, or reopens it on redelivery so the objects are re-verified (the metadata erase
     * has just re-run in the same transaction). Must run inside the metadata erase transaction.
     */
    public void open(UUID ackEventId, UUID erasureRequestId, UUID authUserId, Instant now) {
        Timestamp at = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO media_erasure_jobs (ack_event_id, erasure_request_id, auth_user_id, status, attempts,
                    next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING_OBJECTS', 0, ?, ?, ?)
                ON CONFLICT (ack_event_id) DO UPDATE
                    SET status = 'PENDING_OBJECTS', next_attempt_at = EXCLUDED.next_attempt_at,
                        updated_at = EXCLUDED.updated_at, completed_at = NULL
                """, ackEventId, erasureRequestId, authUserId, at, at, at);
    }

    public Optional<Job> find(UUID ackEventId) {
        return jdbc.query("""
                SELECT ack_event_id, erasure_request_id, auth_user_id, status, attempts
                FROM media_erasure_jobs WHERE ack_event_id = ?
                """, (rs, i) -> new Job(
                        rs.getObject("ack_event_id", UUID.class),
                        rs.getObject("erasure_request_id", UUID.class),
                        rs.getObject("auth_user_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("attempts")),
                ackEventId).stream().findFirst();
    }

    /**
     * Claims up to {@code limit} due pending jobs by pushing their next attempt to {@code leaseUntil},
     * so concurrent instances skip them; the claim itself is one atomic statement.
     */
    public List<UUID> claimDue(int limit, Instant now, Instant leaseUntil) {
        return jdbc.queryForList("""
                UPDATE media_erasure_jobs SET next_attempt_at = ?, updated_at = ?
                WHERE ack_event_id IN (
                    SELECT ack_event_id FROM media_erasure_jobs
                    WHERE status = 'PENDING_OBJECTS' AND next_attempt_at <= ?
                    ORDER BY next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING ack_event_id
                """, UUID.class, Timestamp.from(leaseUntil), Timestamp.from(now), Timestamp.from(now), limit);
    }

    /** Soft-deleted rows of the user whose stored object is not yet confirmed deleted. */
    public List<PendingObject> pendingObjects(UUID authUserId) {
        return jdbc.query("""
                SELECT id, object_key FROM media_files
                WHERE owner_user_id = ? AND status = 'DELETED' AND object_deleted_at IS NULL
                ORDER BY created_at, id
                """, (rs, i) -> new PendingObject(rs.getObject("id", UUID.class), rs.getString("object_key")),
                authUserId);
    }

    public long countPendingObjects(UUID authUserId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM media_files
                WHERE owner_user_id = ? AND status = 'DELETED' AND object_deleted_at IS NULL
                """, Long.class, authUserId);
        return count == null ? 0 : count;
    }

    public void markObjectDeleted(UUID mediaId, Instant now) {
        jdbc.update("UPDATE media_files SET object_deleted_at = ? WHERE id = ? AND object_deleted_at IS NULL",
                Timestamp.from(now), mediaId);
    }

    /** Locks the job row if it is still pending; call inside the completion transaction. */
    public boolean lockPending(UUID ackEventId) {
        return !jdbc.queryForList(
                "SELECT ack_event_id FROM media_erasure_jobs WHERE ack_event_id = ? AND status = 'PENDING_OBJECTS' FOR UPDATE",
                UUID.class, ackEventId).isEmpty();
    }

    public void markAckQueued(UUID ackEventId, Instant now) {
        jdbc.update("""
                UPDATE media_erasure_jobs SET status = 'ACK_QUEUED', completed_at = ?, updated_at = ?, last_error = NULL
                WHERE ack_event_id = ?
                """, Timestamp.from(now), Timestamp.from(now), ackEventId);
    }

    /** Leaves the job pending and schedules its next attempt. */
    public void scheduleRetry(UUID ackEventId, String error, Instant nextAttemptAt, Instant now) {
        jdbc.update("""
                UPDATE media_erasure_jobs
                SET attempts = attempts + 1, last_error = ?, next_attempt_at = ?, updated_at = ?
                WHERE ack_event_id = ? AND status = 'PENDING_OBJECTS'
                """, truncate(error), Timestamp.from(nextAttemptAt), Timestamp.from(now), ackEventId);
    }

    /** Makes the job due again without counting a failed attempt. */
    public void makeDue(UUID ackEventId, Instant now) {
        jdbc.update("""
                UPDATE media_erasure_jobs SET next_attempt_at = ?, updated_at = ?
                WHERE ack_event_id = ? AND status = 'PENDING_OBJECTS'
                """, Timestamp.from(now), Timestamp.from(now), ackEventId);
    }

    public long countPendingJobs() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_erasure_jobs WHERE status = 'PENDING_OBJECTS'", Long.class);
        return count == null ? 0 : count;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
