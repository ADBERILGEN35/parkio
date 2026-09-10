package com.parkio.notification.application;

import com.parkio.notification.application.event.UserErasureRequestedEvent;
import com.parkio.notification.infrastructure.client.AuthErasureAckClient;
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

@Service
public class AccountErasureHandler {

    public static final String SERVICE_NAME = "notification";

    private static final Logger log = LoggerFactory.getLogger(AccountErasureHandler.class);

    private final ErasedUserTombstoneJpaRepository tombstones;
    private final JdbcTemplate jdbc;
    private final AuthErasureAckClient ackClient;
    private final Clock clock;

    public AccountErasureHandler(
            ErasedUserTombstoneJpaRepository tombstones,
            JdbcTemplate jdbc,
            AuthErasureAckClient ackClient,
            Clock clock) {
        this.tombstones = tombstones;
        this.jdbc = jdbc;
        this.ackClient = ackClient;
        this.clock = clock;
    }

    @Transactional
    public void handle(UserErasureRequestedEvent event) {
        eraseLocal(event.authUserId());
        UUID ackEventId = UUID.nameUUIDFromBytes(
                (event.erasureRequestId() + ":" + SERVICE_NAME).getBytes(StandardCharsets.UTF_8));
        ackClient.acknowledge(ackEventId, event.erasureRequestId(), event.authUserId(), "SUCCESS");
        log.info("erasure completed requestId={} service={} status=SUCCESS",
                event.erasureRequestId(), SERVICE_NAME);
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
