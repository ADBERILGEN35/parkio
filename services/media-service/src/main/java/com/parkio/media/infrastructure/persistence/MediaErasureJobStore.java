package com.parkio.media.infrastructure.persistence;

import com.parkio.media.application.port.MediaOwnerFence;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Durable state of media account erasure (V14, V15): one {@code media_erasure_jobs} row per
 * consumed erase request event, kept only while the user's stored objects are not all confirmed
 * gone, and the user's remaining {@code media_files} rows, which are deleted one by one as their
 * objects are confirmed. An attempt works under a claim: a fresh token plus a lease end held in
 * {@code next_attempt_at}; only the holder of an unexpired claim may finalize the job. Every
 * method is a single statement, so outside a caller's transaction it commits on its own.
 */
@Component
public class MediaErasureJobStore {

    private static final int MAX_ERROR_LENGTH = 512;

    private final JdbcTemplate jdbc;
    private final MediaOwnerFence ownerFence;

    public MediaErasureJobStore(JdbcTemplate jdbc) {
        this(jdbc, new MediaOwnerFenceAdapter(jdbc));
    }

    @Autowired
    public MediaErasureJobStore(JdbcTemplate jdbc, MediaOwnerFence ownerFence) {
        this.jdbc = jdbc;
        this.ownerFence = ownerFence;
    }

    /**
     * A pending job; {@code ackEventId} is the deterministic ACK event id of the shared contract,
     * and {@code authUserId} is what the remaining work needs to find the user's media. A
     * restore-replay job (V17) carries {@code restore} instead of an {@code erasureRequestId}.
     */
    public record Job(UUID ackEventId, UUID erasureRequestId, UUID authUserId, int attempts, RestoreBinding restore) {

        /** A job of a live erase request. */
        public Job(UUID ackEventId, UUID erasureRequestId, UUID authUserId, int attempts) {
            this(ackEventId, erasureRequestId, authUserId, attempts, null);
        }

        /** What the job erases for, for logs: the erase request, or the recovery attempt it replays. */
        public String subject() {
            return restore == null ? "requestId=" + erasureRequestId : "recoveryAttemptId=" + restore.recoveryAttemptId();
        }
    }

    /**
     * The restore binding of a restore-replay job (V17): its SUCCESS is the restore ACK for this
     * recovery attempt, restored dataset and erasure set (docs/architecture/erasure-restore-replay-contract.md).
     */
    public record RestoreBinding(UUID recoveryAttemptId, String restoredDatasetId, String erasureSetDigest) {
    }

    /** A claim on a job: only its holder may record progress on the job or finalize it. */
    public record Claim(UUID ackEventId, UUID token) {
    }

    /** A media row of the user whose stored object is not yet confirmed gone. */
    public record StoredMedia(UUID mediaId, String bucket, String objectKey) {
    }

    /**
     * An object write of the user from the {@code media_object_writes} ledger (V16): {@code applied}
     * when the store confirmed it or its object was observed; otherwise its outcome is unknown.
     */
    public record ObjectWrite(UUID writeId, String bucket, String objectKey, boolean applied) {
    }

    /**
     * Opens the job, or touches it on redelivery (the metadata erase has just re-run in the same
     * transaction). The job starts unclaimed; {@code nextAttemptAt} keeps the scheduled poll away
     * while the caller's own attempt claims it. Must run inside the metadata erase transaction.
     */
    public void open(UUID ackEventId, UUID erasureRequestId, UUID authUserId, Instant now, Instant nextAttemptAt) {
        jdbc.update("""
                INSERT INTO media_erasure_jobs (ack_event_id, erasure_request_id, auth_user_id, attempts,
                    next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?, ?)
                ON CONFLICT (ack_event_id) DO UPDATE SET updated_at = EXCLUDED.updated_at
                """, ackEventId, erasureRequestId, authUserId, Timestamp.from(nextAttemptAt), Timestamp.from(now),
                Timestamp.from(now));
    }

    /**
     * Opens or touches a restore-replay job, under the same rules as {@link #open}. Must run inside
     * the metadata erase transaction.
     */
    public void openRestore(UUID ackEventId, RestoreBinding restore, UUID authUserId, Instant now, Instant nextAttemptAt) {
        jdbc.update("""
                INSERT INTO media_erasure_jobs (ack_event_id, recovery_attempt_id, restored_dataset_id,
                    erasure_set_digest, auth_user_id, attempts, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)
                ON CONFLICT (ack_event_id) DO UPDATE SET updated_at = EXCLUDED.updated_at
                """, ackEventId, restore.recoveryAttemptId(), restore.restoredDatasetId(), restore.erasureSetDigest(),
                authUserId, Timestamp.from(nextAttemptAt), Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * Takes the user's erasure fence exclusively for the rest of the caller's transaction: waits
     * for media writes already admitted and refuses new ones until it ends (see
     * {@link MediaOwnerFence}). Call it before any other statement of that transaction.
     */
    public void holdOwner(UUID authUserId) {
        ownerFence.holdForErasure(authUserId);
    }

    public Optional<Job> find(UUID ackEventId) {
        return jdbc.query("""
                SELECT ack_event_id, erasure_request_id, auth_user_id, attempts,
                    recovery_attempt_id, restored_dataset_id, erasure_set_digest
                FROM media_erasure_jobs WHERE ack_event_id = ?
                """, (rs, i) -> new Job(
                        rs.getObject("ack_event_id", UUID.class),
                        rs.getObject("erasure_request_id", UUID.class),
                        rs.getObject("auth_user_id", UUID.class),
                        rs.getInt("attempts"),
                        rs.getObject("recovery_attempt_id", UUID.class) == null ? null : new RestoreBinding(
                                rs.getObject("recovery_attempt_id", UUID.class),
                                rs.getString("restored_dataset_id"),
                                rs.getString("erasure_set_digest"))),
                ackEventId).stream().findFirst();
    }

    /**
     * Claims the job for one attempt if nobody holds an unexpired claim on it (an unclaimed job in
     * backoff may be claimed at once, as on a redelivery); empty if someone else holds it.
     */
    public Optional<UUID> claim(UUID ackEventId, Instant now, Instant leaseUntil) {
        UUID token = UUID.randomUUID();
        int claimed = jdbc.update("""
                UPDATE media_erasure_jobs SET claim_token = ?, next_attempt_at = ?, updated_at = ?
                WHERE ack_event_id = ? AND (claim_token IS NULL OR next_attempt_at <= ?)
                """, token, Timestamp.from(leaseUntil), Timestamp.from(now), ackEventId, Timestamp.from(now));
        return claimed == 1 ? Optional.of(token) : Optional.empty();
    }

    /**
     * Claims up to {@code limit} due jobs (unclaimed and due, or with an expired claim) by giving
     * them a fresh token and pushing their next attempt to {@code leaseUntil}, so concurrent
     * instances skip them; the claim itself is one atomic statement.
     */
    public List<Claim> claimDue(int limit, Instant now, Instant leaseUntil) {
        UUID token = UUID.randomUUID();
        return jdbc.queryForList("""
                UPDATE media_erasure_jobs SET claim_token = ?, next_attempt_at = ?, updated_at = ?
                WHERE ack_event_id IN (
                    SELECT ack_event_id FROM media_erasure_jobs
                    WHERE next_attempt_at <= ?
                    ORDER BY next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING ack_event_id
                """, UUID.class, token, Timestamp.from(leaseUntil), Timestamp.from(now), Timestamp.from(now), limit)
                .stream().map(id -> new Claim(id, token)).toList();
    }

    /**
     * Locks the job if the claim is still held and unexpired at {@code now}; call inside the
     * completion transaction, after {@link #holdOwner}. False if the claim expired or was taken over,
     * or the job is gone.
     */
    public boolean lockClaim(UUID ackEventId, UUID token, Instant now) {
        return !jdbc.queryForList("""
                SELECT ack_event_id FROM media_erasure_jobs
                WHERE ack_event_id = ? AND claim_token = ? AND next_attempt_at > ?
                FOR UPDATE
                """, UUID.class, ackEventId, token, Timestamp.from(now)).isEmpty();
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

    /**
     * Up to {@code limit} of the user's recorded object writes, in write id order, after
     * {@code afterWriteId} ({@code null}: from the first), so a caller can go through all of them.
     */
    public List<ObjectWrite> unsettledWrites(UUID authUserId, UUID afterWriteId, int limit) {
        RowMapper<ObjectWrite> write = (rs, i) -> new ObjectWrite(
                rs.getObject("id", UUID.class), rs.getString("bucket_name"), rs.getString("object_key"),
                "APPLIED".equals(rs.getString("state")));
        if (afterWriteId == null) {
            return jdbc.query("""
                    SELECT id, bucket_name, object_key, state FROM media_object_writes
                    WHERE owner_user_id = ?
                    ORDER BY id
                    LIMIT ?
                    """, write, authUserId, limit);
        }
        return jdbc.query("""
                SELECT id, bucket_name, object_key, state FROM media_object_writes
                WHERE owner_user_id = ? AND id > ?
                ORDER BY id
                LIMIT ?
                """, write, authUserId, afterWriteId, limit);
    }

    public long countUnsettledWrites(UUID authUserId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM media_object_writes WHERE owner_user_id = ?",
                Long.class, authUserId);
        return count == null ? 0 : count;
    }

    /** Writes of any user whose outcome is still unknown (operations gauge). */
    public long countWritesOfUnknownOutcome() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM media_object_writes WHERE state = 'PENDING'",
                Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Records that an object of the user's key was observed: every write of that key whose outcome
     * was unknown has been applied, and cannot apply again (an upload transmits its PUT once, under
     * a fresh key). Must commit before that object is removed, or the evidence would be lost with it.
     */
    public int markObserved(UUID authUserId, String bucket, String objectKey, Instant now) {
        return jdbc.update("""
                UPDATE media_object_writes SET state = 'APPLIED', updated_at = ?
                WHERE owner_user_id = ? AND bucket_name = ? AND object_key = ? AND state = 'PENDING'
                """, Timestamp.from(now), authUserId, bucket, objectKey);
    }

    /** Forgets a write whose object was applied and is now confirmed gone. */
    public void forgetWrite(UUID writeId) {
        jdbc.update("DELETE FROM media_object_writes WHERE id = ?", writeId);
    }

    /** Deletes a media row and its validation results; call inside a transaction. */
    public void deleteMedia(UUID mediaId) {
        jdbc.update("DELETE FROM media_validation_results WHERE media_id = ?", mediaId);
        jdbc.update("DELETE FROM media_files WHERE id = ?", mediaId);
    }

    public void deleteIdempotencyRecords(UUID authUserId) {
        jdbc.update("DELETE FROM idempotency_records WHERE user_id = ?", authUserId);
    }

    /** Removes the finished job; call in the transaction that queues its SUCCESS ACK. */
    public void delete(UUID ackEventId) {
        jdbc.update("DELETE FROM media_erasure_jobs WHERE ack_event_id = ?", ackEventId);
    }

    /**
     * Counts a failed attempt, releases the claim and schedules the next attempt; a no-op unless
     * the caller still holds the claim (a stale attempt never overwrites a newer one's state).
     */
    public boolean scheduleRetry(UUID ackEventId, UUID token, String error, Instant nextAttemptAt, Instant now) {
        return jdbc.update("""
                UPDATE media_erasure_jobs
                SET attempts = attempts + 1, last_error = ?, next_attempt_at = ?, updated_at = ?, claim_token = NULL
                WHERE ack_event_id = ? AND claim_token = ?
                """, truncate(error), Timestamp.from(nextAttemptAt), Timestamp.from(now), ackEventId, token) == 1;
    }

    /**
     * Releases the claim without counting a failed attempt and makes the job due at
     * {@code nextAttemptAt}; a no-op unless the caller still holds the claim.
     */
    public boolean release(UUID ackEventId, UUID token, Instant nextAttemptAt, Instant now) {
        return jdbc.update("""
                UPDATE media_erasure_jobs SET claim_token = NULL, next_attempt_at = ?, updated_at = ?
                WHERE ack_event_id = ? AND claim_token = ?
                """, Timestamp.from(nextAttemptAt), Timestamp.from(now), ackEventId, token) == 1;
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
