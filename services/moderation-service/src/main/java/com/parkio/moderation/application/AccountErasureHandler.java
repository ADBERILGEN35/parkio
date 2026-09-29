package com.parkio.moderation.application;

import com.parkio.moderation.application.event.UserErasureRequestedEvent;
import com.parkio.moderation.infrastructure.client.AuthErasureAckClient;
import com.parkio.moderation.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import com.parkio.moderation.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
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
    public static final String SERVICE_NAME = "moderation";

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
        jdbc.update("""
                UPDATE user_reports SET reporter_user_id = ?
                WHERE reporter_user_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM user_reports other
                      WHERE other.reporter_user_id = ?
                        AND other.target_type = user_reports.target_type
                        AND other.target_id = user_reports.target_id
                        AND other.reason = user_reports.reason
                  )
                """, ERASED_USER_SENTINEL, authUserId, ERASED_USER_SENTINEL);
        jdbc.update("UPDATE user_reports SET target_id = ? WHERE target_type = 'USER' AND target_id = ?",
                ERASED_USER_SENTINEL, authUserId);
        jdbc.update("UPDATE moderation_cases SET owner_user_id = ? WHERE owner_user_id = ?",
                ERASED_USER_SENTINEL, authUserId);
        jdbc.update("UPDATE moderation_cases SET target_id = ? WHERE target_type = 'USER' AND target_id = ?",
                ERASED_USER_SENTINEL, authUserId);
        jdbc.update("UPDATE moderation_cases SET assigned_moderator_id = ? WHERE assigned_moderator_id = ?",
                ERASED_USER_SENTINEL, authUserId);
        jdbc.update("""
                UPDATE appeals SET appeal_user_id = ?
                WHERE appeal_user_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM appeals other
                      WHERE other.case_id = appeals.case_id
                        AND other.appeal_user_id = ?
                  )
                """, ERASED_USER_SENTINEL, authUserId, ERASED_USER_SENTINEL);
        jdbc.update("UPDATE user_violations SET user_id = ? WHERE user_id = ?",
                ERASED_USER_SENTINEL, authUserId);
    }
}
