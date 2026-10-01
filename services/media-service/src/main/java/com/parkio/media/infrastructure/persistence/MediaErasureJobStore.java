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
 * erase request event, kept only while the user's stored objects are not all confirmed gone, and
 * the user's remaining {@code media_files} rows, which are deleted one by one as their objects are
 * confirmed. Every method is a single statement, so outside a caller's transaction it commits on
 * its own.
 */
@Component
public class MediaErasureJobStore {

    private static final int MAX_ERROR_LENGTH = 512;

    private final JdbcTemplate jdbc;

    public MediaErasureJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A pending job; {@code ackEventId} is the deterministic ACK event id of the shared contract,
     * and {@code authUserId} is what the remaining work needs to find the user's media.
     */
    public record Job(UUID ackEventId, UUID erasureRequestId, UUID authUserId, int attempts) {
    }

    /** A media row of the user whose stored object is not yet confirmed gone. */
    public record StoredMedia(UUID mediaId, String bucket, String objectKey) {
    }

    /**
     * Opens the job, or reopens it on redelivery (the metadata erase has just re-run in the same
     * transaction). The first attempt holds it until {@code leaseUntil}, so a concurrent poll does
     * not pick it up while that attempt runs. Must run inside the metadata erase transaction.
     */
    public void open(UUID ackEventId, UUID erasureRequestId, UUID authUserId, Instant now, Instant leaseUntil) {
        jdbc.update("""
                INSERT INTO media_erasure_jobs (ack_event_id, erasure_request_id, auth_user_id, attempts,
                    next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?, ?)
                ON CONFLICT (ack_event_id) DO UPDATE
                    SET next_attempt_at = EXCLUDED.next_attempt_at, updated_at = EXCLUDED.updated_at
                """, ackEventId, erasureRequestId, authUserId, Timestamp.from(leaseUntil), Timestamp.from(now),
                Timestamp.from(now));
    }

    public Optional<Job> find(UUID ackEventId) {
        return jdbc.query("""
                SELECT ack_event_id, erasure_request_id, auth_user_id, attempts
                FROM media_erasure_jobs WHERE ack_event_id = ?
                """, (rs, i) -> new Job(
                        rs.getObject("ack_event_id", UUID.class),
                        rs.getObject("erasure_request_id", UUID.class),
                        rs.getObject("auth_user_id", UUID.class),
                        rs.getInt("attempts")),
                ackEventId).stream().findFirst();
    }

    /**
     * Claims up to {@code limit} due jobs by pushing their next attempt to {@code leaseUntil}, so
     * concurrent instances skip them; the claim itself is one atomic statement.
     */
    public List<UUID> claimDue(int limit, Instant now, Instant leaseUntil) {
        return jdbc.queryForList("""
                UPDATE media_erasure_jobs SET next_attempt_at = ?, updated_at = ?
                WHERE ack_event_id IN (
                    SELECT ack_event_id FROM media_erasure_jobs
                    WHERE next_attempt_at <= ?
                    ORDER BY next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING ack_event_id
                """, UUID.class, Timestamp.from(leaseUntil), Timestamp.from(now), Timestamp.from(now), limit);
    }

    /** Every media row the user still owns, whatever its status. */
    public List<StoredMedia> remainingMedia(UUID authUserId) {
        return jdbc.query("""
                SELECT id, bucket_name, object_key FROM media_files
                WHERE owner_user_id = ?
                ORDER BY created_at, id
                """, (rs, i) -> new StoredMedia(
                        rs.getObject("id", UUID.class), rs.getString("bucket_name"), rs.getString("object_key")),
                authUserId);
    }

    public long countMedia(UUID authUserId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ?", Long.class,
                authUserId);
        return count == null ? 0 : count;
    }

    /** Deletes a media row and its validation results; call inside a transaction. */
    public void deleteMedia(UUID mediaId) {
        jdbc.update("DELETE FROM media_validation_results WHERE media_id = ?", mediaId);
        jdbc.update("DELETE FROM media_files WHERE id = ?", mediaId);
    }

    public void deleteIdempotencyRecords(UUID authUserId) {
        jdbc.update("DELETE FROM idempotency_records WHERE user_id = ?", authUserId);
    }

    /** Locks the job row if it still exists; call inside the completion transaction. */
    public boolean lock(UUID ackEventId) {
        return !jdbc.queryForList(
                "SELECT ack_event_id FROM media_erasure_jobs WHERE ack_event_id = ? FOR UPDATE",
                UUID.class, ackEventId).isEmpty();
    }

    /** Removes the finished job; call in the transaction that queues its SUCCESS ACK. */
    public void delete(UUID ackEventId) {
        jdbc.update("DELETE FROM media_erasure_jobs WHERE ack_event_id = ?", ackEventId);
    }

    /** Counts a failed attempt and schedules the next one. */
    public void scheduleRetry(UUID ackEventId, String error, Instant nextAttemptAt, Instant now) {
        jdbc.update("""
                UPDATE media_erasure_jobs
                SET attempts = attempts + 1, last_error = ?, next_attempt_at = ?, updated_at = ?
                WHERE ack_event_id = ?
                """, truncate(error), Timestamp.from(nextAttemptAt), Timestamp.from(now), ackEventId);
    }

    /** Makes the job due again without counting a failed attempt. */
    public void makeDue(UUID ackEventId, Instant now) {
        jdbc.update("UPDATE media_erasure_jobs SET next_attempt_at = ?, updated_at = ? WHERE ack_event_id = ?",
                Timestamp.from(now), Timestamp.from(now), ackEventId);
    }

    public long countPendingJobs() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM media_erasure_jobs", Long.class);
        return count == null ? 0 : count;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
