package com.parkio.notification.application;

import com.parkio.notification.application.event.UserErasureRequestedEvent;
import com.parkio.notification.application.port.ErasureAckOutbox;
import com.parkio.notification.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.notification.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import com.parkio.notification.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
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
 * within the same transaction. Nothing is sent to auth from here: {@code NotificationOutboxRelay}
 * publishes the ACK only after the row has committed, and retries until the broker acks it
 * (docs/architecture/erasure-ack-outbox-contract.md).
 */
@Service
public class AccountErasureHandler {

    public static final String SERVICE_NAME = "notification";

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
        jdbc.update("UPDATE notification_delivery_attempts SET device_token_id = NULL WHERE user_id = ?",
                authUserId);
        jdbc.update("DELETE FROM notification_delivery_attempts WHERE user_id = ?", authUserId);
        jdbc.update("DELETE FROM notifications WHERE user_id = ?", authUserId);
        jdbc.update("DELETE FROM device_tokens WHERE user_id = ?", authUserId);
        jdbc.update("DELETE FROM notification_preferences WHERE user_id = ?", authUserId);
    }
}
