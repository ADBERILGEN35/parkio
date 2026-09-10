package com.parkio.gamification.application;

import com.parkio.gamification.application.event.UserErasureRequestedEvent;
import com.parkio.gamification.infrastructure.client.AuthErasureAckClient;
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

@Service
public class AccountErasureHandler {

    public static final UUID ERASED_USER_SENTINEL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    public static final String SERVICE_NAME = "gamification";

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
        jdbc.update("DELETE FROM user_level_progress WHERE user_id = ?", authUserId);
        jdbc.update("DELETE FROM trust_scores WHERE user_id = ?", authUserId);
        jdbc.update("DELETE FROM contribution_snapshots WHERE user_id = ?", authUserId);
        jdbc.update("UPDATE point_transactions SET user_id = ? WHERE user_id = ?",
                ERASED_USER_SENTINEL, authUserId);
    }
}
