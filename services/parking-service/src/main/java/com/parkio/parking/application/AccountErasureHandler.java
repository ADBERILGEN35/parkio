package com.parkio.parking.application;

import com.parkio.parking.application.event.UserErasureRequestedEvent;
import com.parkio.parking.infrastructure.client.AuthErasureAckClient;
import com.parkio.parking.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import com.parkio.parking.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
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
    public static final String SERVICE_NAME = "parking";

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
        jdbc.update("DELETE FROM parking_sessions WHERE user_id = ?", authUserId);
        jdbc.update("DELETE FROM parking_spot_search_logs WHERE searcher_user_id = ?", authUserId);
        jdbc.update("DELETE FROM parking_spot_view_logs WHERE viewer_user_id = ?", authUserId);
        jdbc.update("DELETE FROM parking_spot_verifications WHERE verifier_user_id = ?", authUserId);
        jdbc.update(
                "UPDATE parking_spots SET owner_user_id = ?, media_id = NULL WHERE owner_user_id = ?",
                ERASED_USER_SENTINEL, authUserId);
        jdbc.update("DELETE FROM idempotency_records WHERE user_id = ?", authUserId);
        anonymizeSubject("trust_ledger", "subject_id", authUserId);
        anonymizeTrustSnapshot(authUserId);
        anonymizeSubject("fraud_evaluation_ledger", "subject_id", authUserId);
        anonymizeSubject("pending_reward_ledger", "reward_subject_id", authUserId);
    }

    private void anonymizeSubject(String table, String column, UUID authUserId) {
        jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE " + column + " = ?",
                ERASED_USER_SENTINEL, authUserId);
    }

    private void anonymizeTrustSnapshot(UUID authUserId) {
        jdbc.update("""
                UPDATE trust_snapshot SET subject_id = ?
                WHERE subject_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM trust_snapshot other
                      WHERE other.subject_type = trust_snapshot.subject_type
                        AND other.trust_domain = trust_snapshot.trust_domain
                        AND other.subject_id = ?
                  )
                """, ERASED_USER_SENTINEL, authUserId, ERASED_USER_SENTINEL);
        jdbc.update("DELETE FROM trust_snapshot WHERE subject_id = ?", authUserId);
    }
}
