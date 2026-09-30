package com.parkio.gamification.application;

import com.parkio.gamification.application.event.UserErasureRequestedEvent;
import com.parkio.gamification.application.port.OutboxEventAppender;
import com.parkio.gamification.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.gamification.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import com.parkio.gamification.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases this service's user-keyed rows and records the SUCCESS ACK in the transactional
 * outbox within the same transaction. Nothing is sent to auth from here: the outbox relay
 * publishes the ACK only after the row has committed, and retries until the broker acks it
 * (docs/architecture/erasure-ack-outbox-contract.md).
 */
@Service
public class AccountErasureHandler {

    public static final UUID ERASED_USER_SENTINEL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    public static final String SERVICE_NAME = "gamification";

    private static final Logger log = LoggerFactory.getLogger(AccountErasureHandler.class);

    private final ErasedUserTombstoneJpaRepository tombstones;
    private final JdbcTemplate jdbc;
    private final OutboxEventAppender outbox;
    private final Clock clock;

    public AccountErasureHandler(
            ErasedUserTombstoneJpaRepository tombstones,
            JdbcTemplate jdbc,
            OutboxEventAppender outbox,
            Clock clock) {
        this.tombstones = tombstones;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void handle(UserErasureRequestedEvent event) {
        eraseLocal(event.authUserId());
        outbox.append(new UserErasureAcknowledgedEvent(
                ackEventId(event), event.erasureRequestId(), event.authUserId(),
                SERVICE_NAME, "SUCCESS", clock.instant()));
        log.info("erasure committed requestId={} service={} status=SUCCESS_QUEUED",
                event.erasureRequestId(), SERVICE_NAME);
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
        jdbc.update("DELETE FROM user_level_progress WHERE user_id = ?", authUserId);
        jdbc.update("DELETE FROM trust_scores WHERE user_id = ?", authUserId);
        jdbc.update("DELETE FROM contribution_snapshots WHERE user_id = ?", authUserId);
        jdbc.update("UPDATE point_transactions SET user_id = ? WHERE user_id = ?",
                ERASED_USER_SENTINEL, authUserId);
    }
}
