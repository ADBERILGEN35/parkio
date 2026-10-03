package com.parkio.aivalidation.application;

import com.parkio.aivalidation.application.event.UserErasureRequestedEvent;
import com.parkio.aivalidation.application.event.UserErasureRestoreReplayRequestedEvent;
import com.parkio.aivalidation.application.port.ErasureAckOutbox;
import com.parkio.aivalidation.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.aivalidation.domain.event.UserErasureRestoreAcknowledgedEvent;
import com.parkio.aivalidation.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import com.parkio.aivalidation.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases this service's user-keyed data and records the SUCCESS ACK in the transactional outbox
 * within the same transaction. Nothing is sent to auth from here: {@code AiValidationOutboxRelay}
 * publishes the ACK only after the row has committed, and retries until the broker acks it
 * (docs/architecture/erasure-ack-outbox-contract.md).
 */
@Service
public class AccountErasureHandler {

    public static final UUID ERASED_USER_SENTINEL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    public static final String SERVICE_NAME = "ai-validation";

    private static final Logger log = LoggerFactory.getLogger(AccountErasureHandler.class);

    private final ErasedUserTombstoneJpaRepository tombstones;
    private final JdbcTemplate jdbc;
    private final ErasureAckOutbox ackOutbox;
    private final Clock clock;

    public AccountErasureHandler(
            ErasedUserTombstoneJpaRepository tombstones,
            JdbcTemplate jdbc,
            ErasureAckOutbox ackOutbox,
            Clock clock) {
        this.tombstones = tombstones;
        this.jdbc = jdbc;
        this.ackOutbox = ackOutbox;
        this.clock = clock;
    }

    @Transactional
    public void handle(UserErasureRequestedEvent event) {
        eraseLocal(event.authUserId());
        ackOutbox.append(new UserErasureAcknowledgedEvent(
                ackEventId(event), event.erasureRequestId(), event.authUserId(),
                SERVICE_NAME, "SUCCESS", clock.instant()));
        log.info("erasure committed requestId={} service={} status=SUCCESS_QUEUED",
                event.erasureRequestId(), SERVICE_NAME);
    }

    /**
     * Replays one user's erasure for an isolated recovery and records the attempt-bound restore ACK
     * in the outbox within the same transaction (docs/architecture/erasure-restore-replay-contract.md).
     * The erase is the live one, so a replay is idempotent; the ACK echoes the attempt, dataset and
     * erasure-set digest it was asked for, and the coordinator decides whether they match.
     */
    @Transactional
    public void replayForRestore(UserErasureRestoreReplayRequestedEvent event) {
        eraseLocal(event.authUserId());
        ackOutbox.appendRestoreAck(new UserErasureRestoreAcknowledgedEvent(
                restoreAckEventId(event), event.recoveryAttemptId(), event.restoredDatasetId(),
                event.erasureSetDigest(), event.authUserId(), SERVICE_NAME, "SUCCESS", clock.instant()));
        log.info("erasure restore replay committed attempt={} service={} status=SUCCESS_QUEUED",
                event.recoveryAttemptId(), SERVICE_NAME);
    }

    /** One restore ACK per consumed replay event; a redelivery re-derives the same id. */
    static UUID restoreAckEventId(UserErasureRestoreReplayRequestedEvent event) {
        String key = event.eventId() + ":" + event.recoveryAttemptId() + ":" + event.authUserId() + ":"
                + SERVICE_NAME + ":restore-ack";
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One ACK per consumed request event: a redelivery of the same request event maps to the same
     * outbox row (appended once), while a coordinator replay, which carries a new request eventId,
     * queues a fresh ACK. Auth deduplicates ACKs by eventId and keys them by (request, service).
     */
    static UUID ackEventId(UserErasureRequestedEvent event) {
        String key = event.eventId() + ":" + event.erasureRequestId() + ":" + SERVICE_NAME + ":ack";
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private void eraseLocal(UUID authUserId) {
        tombstones.save(new ErasedUserTombstoneEntity(authUserId, clock.instant()));
        jdbc.update("UPDATE ai_validation_results SET requested_by_user_id = ? WHERE requested_by_user_id = ?",
                ERASED_USER_SENTINEL, authUserId);
    }
}
