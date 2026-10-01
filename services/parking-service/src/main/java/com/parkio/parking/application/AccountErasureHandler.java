package com.parkio.parking.application;

import com.parkio.parking.application.event.UserErasureRequestedEvent;
import com.parkio.parking.application.port.ErasureAckOutbox;
import com.parkio.parking.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.parking.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import com.parkio.parking.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases this service's user-keyed data and records the SUCCESS ACK in the transactional outbox
 * within the same transaction. Nothing is sent to auth from here: {@code ParkingOutboxRelay}
 * publishes the ACK only after the row has committed, and retries until the broker acks it
 * (docs/architecture/erasure-ack-outbox-contract.md).
 */
@Service
public class AccountErasureHandler {

    public static final UUID ERASED_USER_SENTINEL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    public static final String SERVICE_NAME = "parking";

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
        jdbc.update("DELETE FROM parking_sessions WHERE user_id = ?", authUserId);
        jdbc.update("DELETE FROM parking_spot_search_logs WHERE searcher_user_id = ?", authUserId);
        jdbc.update("DELETE FROM parking_spot_view_logs WHERE viewer_user_id = ?", authUserId);
        jdbc.update("DELETE FROM parking_spot_verifications WHERE verifier_user_id = ?", authUserId);
        jdbc.update(
                "UPDATE parking_spots SET owner_user_id = ?, media_id = NULL WHERE owner_user_id = ?",
                ERASED_USER_SENTINEL, authUserId);
        jdbc.update("DELETE FROM idempotency_records WHERE user_id = ?", authUserId);
        // The shadow ledgers also serialize the subject into their JSON columns (evidence,
        // snapshots, evaluations, contributions); rewrite it there too, so no copy of the user id
        // survives an anonymized row.
        anonymizeSubject("trust_ledger", "subject_id", authUserId,
                "evidence_json", "previous_snapshot_json", "evaluation_json");
        anonymizeTrustSnapshot(authUserId);
        anonymizeSubject("fraud_evaluation_ledger", "subject_id", authUserId, "evaluation_snapshot_json");
        anonymizeSubject("pending_reward_ledger", "reward_subject_id", authUserId,
                "contribution_json", "evaluation_json");
    }

    private void anonymizeSubject(String table, String column, UUID authUserId, String... jsonColumns) {
        StringBuilder sql = new StringBuilder("UPDATE " + table + " SET " + column + " = ?");
        List<Object> args = new ArrayList<>(List.of(ERASED_USER_SENTINEL));
        for (String json : jsonColumns) {
            sql.append(", ").append(json).append(" = replace(").append(json).append(", ?, ?)");
            args.add(authUserId.toString());
            args.add(ERASED_USER_SENTINEL.toString());
        }
        sql.append(" WHERE ").append(column).append(" = ?");
        args.add(authUserId);
        jdbc.update(sql.toString(), args.toArray());
    }

    /**
     * Moves the user's trust snapshots to the sentinel. A snapshot's id is derived from its
     * subject ({@code TrustPersistenceMapper}), so the row is re-keyed to the sentinel-derived id
     * and its JSON rewritten; where the sentinel already holds a snapshot for that type and
     * domain, the user's row is deleted instead.
     */
    private void anonymizeTrustSnapshot(UUID authUserId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, subject_type, trust_domain FROM trust_snapshot WHERE subject_id = ?", authUserId);
        for (Map<String, Object> row : rows) {
            String subjectType = (String) row.get("subject_type");
            String trustDomain = (String) row.get("trust_domain");
            UUID sentinelSnapshotId = UUID.nameUUIDFromBytes(
                    ("trust-snapshot|" + subjectType + "|" + ERASED_USER_SENTINEL + "|" + trustDomain)
                            .getBytes(StandardCharsets.UTF_8));
            int moved = jdbc.update("""
                    UPDATE trust_snapshot
                    SET id = ?, subject_id = ?, snapshot_json = replace(snapshot_json, ?, ?)
                    WHERE id = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM trust_snapshot other
                          WHERE other.subject_type = ? AND other.trust_domain = ? AND other.subject_id = ?
                      )
                    """, sentinelSnapshotId, ERASED_USER_SENTINEL, authUserId.toString(),
                    ERASED_USER_SENTINEL.toString(), row.get("id"), subjectType, trustDomain,
                    ERASED_USER_SENTINEL);
            if (moved == 0) {
                jdbc.update("DELETE FROM trust_snapshot WHERE id = ?", row.get("id"));
            }
        }
    }
}
