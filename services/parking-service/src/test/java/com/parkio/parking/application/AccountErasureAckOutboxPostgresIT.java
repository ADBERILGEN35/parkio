package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.event.UserErasureRequestedEvent;
import com.parkio.parking.application.event.UserErasureRestoreReplayRequestedEvent;
import com.parkio.parking.infrastructure.messaging.ParkingOutboxRelay;
import com.parkio.parking.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import com.parkio.parking.testsupport.PostgisTestImages;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * U05 participant ACK outbox for parking-service against real PostgreSQL (Flyway schema) and a real
 * Kafka broker, with synthetic data only. Proves that the SUCCESS ACK exists only as a committed
 * outbox row written with the local erase, that {@code ParkingOutboxRelay} publishes it to
 * {@code parkio.privacy.erasure} only from that row, that a broker outage after commit leaves it
 * queued for a later relay, that duplicate/replayed delivery follows the shared contract, and that
 * unrelated users and existing event types are unaffected
 * (docs/architecture/erasure-ack-outbox-contract.md).
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AccountErasureAckOutboxPostgresIT {

    private static final String ERASURE_TOPIC = "parkio.privacy.erasure";
    private static final UUID SENTINEL = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgisTestImages.dockerImageName());

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.kafka.ai-validation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired private AccountErasureHandler handler;
    @Autowired private OutboxEventJpaRepository outbox;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void commitFailureLeavesNoEraseNoAckIntentAndNothingIsPublished() {
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seed(user);
        Map<String, Long> before = userRows(user);

        installCommitFailure();
        try {
            assertThatThrownBy(() -> handler.handle(event));
        } finally {
            dropCommitFailure();
        }

        assertThat(userRows(user)).isEqualTo(before);
        assertThat(tombstones(user)).isZero();
        assertThat(ackRows(event.erasureRequestId())).isZero();
        relay(liveBroker()).run();
        assertThat(records(ERASURE_TOPIC, event.erasureRequestId(), Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void successfulEraseCommitsTogetherWithAckRowAndPreservesOtherData() {
        UUID user = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seed(user);
        seed(bystander);
        Map<String, Long> userBefore = userRows(user);
        Map<String, Long> bystanderBefore = userRows(bystander);
        Map<String, Long> retainedBefore = retainedTotals();
        Map<String, Long> sentinelBefore = sentinelRows();

        handler.handle(event);

        assertErased(user);
        Map<String, Object> row = ackRow(event.erasureRequestId());
        assertThat(row.get("published")).isEqualTo(false);
        assertThat(row.get("event_type")).isEqualTo("UserErasureAcknowledged");
        assertThat(userRows(bystander)).isEqualTo(bystanderBefore);
        assertThat(tombstones(bystander)).isZero();
        // Anonymized rows are retained, and exactly the user's rows moved to the sentinel.
        assertThat(retainedTotals()).isEqualTo(retainedBefore);
        sentinelRows().forEach((label, rows) ->
                assertThat(rows - sentinelBefore.get(label)).as(label).isEqualTo(userBefore.get(label)));
    }

    @Test
    void brokerOutageAfterCommitKeepsAckQueuedAndRestartPublishesIt() throws Exception {
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seed(user);
        handler.handle(event);

        // Broker unreachable: send() fails synchronously. The poll counts that failure for the
        // row and completes (U18 CL-F32); the committed ACK row stays queued, not dead-lettered.
        long failuresBefore = jdbc.queryForObject("SELECT COALESCE(SUM(failure_count), 0) FROM outbox_events", Long.class);
        assertThatCode(relay(brokerTemplate("127.0.0.1:1", 2_000))::run).doesNotThrowAnyException();
        Map<String, Object> row = ackRow(event.erasureRequestId());
        assertThat(row.get("published")).isEqualTo(false);
        assertThat(row.get("dead_lettered")).isEqualTo(false);
        // The poll stops dispatching at the first throwing send() and counts that one row, which is
        // this ACK unless an earlier queued row comes first in the batch.
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(failure_count), 0) FROM outbox_events", Long.class))
                .as("exactly one synchronous send failure is counted").isEqualTo(failuresBefore + 1);

        // "Restart": a new relay instance over the same committed row.
        relay(liveBroker()).run();
        assertThat(ackRow(event.erasureRequestId()).get("published")).isEqualTo(true);

        List<ConsumerRecord<String, String>> records = records(ERASURE_TOPIC, event.erasureRequestId(), Duration.ofSeconds(20));
        assertThat(records).hasSize(1);
        JsonNode envelope = objectMapper.readTree(records.get(0).value());
        assertThat(envelope.get("eventType").asText()).isEqualTo("UserErasureAcknowledged");
        assertThat(envelope.get("aggregateType").asText()).isEqualTo("AccountErasure");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(event.erasureRequestId().toString());
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("eventId").asText()).isEqualTo(envelope.get("eventId").asText());
        assertThat(payload.get("eventId").asText()).isEqualTo(AccountErasureHandler.ackEventId(event).toString());
        assertThat(payload.get("erasureRequestId").asText()).isEqualTo(event.erasureRequestId().toString());
        assertThat(payload.get("authUserId").asText()).isEqualTo(user.toString());
        assertThat(payload.get("serviceName").asText()).isEqualTo("parking");
        assertThat(payload.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(payload.hasNonNull("occurredAt")).isTrue();
    }

    @Test
    void duplicateDeliveryQueuesOneAckAndKeepsErasedState() {
        UUID user = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seed(user);
        seed(bystander);
        Map<String, Long> bystanderBefore = userRows(bystander);

        handler.handle(event);
        handler.handle(event);
        relay(liveBroker()).run();
        handler.handle(event);
        relay(liveBroker()).run();

        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);
        assertThat(records(ERASURE_TOPIC, event.erasureRequestId(), Duration.ofSeconds(20))).hasSize(1);
        assertErased(user);
        assertThat(userRows(bystander)).isEqualTo(bystanderBefore);
    }

    @Test
    void redeliveryAfterFailedCommitErasesAndQueuesAckOnce() {
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seed(user);
        installCommitFailure();
        try {
            assertThatThrownBy(() -> handler.handle(event));
        } finally {
            dropCommitFailure();
        }
        assertThat(ackRows(event.erasureRequestId())).isZero();

        handler.handle(event); // Kafka redelivery of the same request event

        assertErased(user);
        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);
    }

    @Test
    void redeliveryStillErasesRowsRecreatedAfterEarlierSuccess() {
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seed(user);
        handler.handle(event);
        seed(user); // synthetic: user-keyed state reappears after the first ACK

        handler.handle(event);

        assertErased(user);
        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);
    }

    @Test
    void coordinatorReplayWithNewRequestEventQueuesFreshAck() {
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        UserErasureRequestedEvent replay = new UserErasureRequestedEvent(
                UUID.randomUUID(), event.erasureRequestId(), user, Instant.now());
        seed(user);

        handler.handle(event);
        handler.handle(replay);

        assertErased(user);
        assertThat(jdbc.queryForList("""
                SELECT event_id FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND aggregate_id = ?
                """, UUID.class, event.erasureRequestId()))
                .containsExactlyInAnyOrder(AccountErasureHandler.ackEventId(event), AccountErasureHandler.ackEventId(replay));
    }

    @Test
    void existingEventTypesStillRouteToTheirOwnTopics() {
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        List<Existing> existing = new ArrayList<>();
        existing.add(insertExistingRow("ParkingSpot", "ParkingSpotCreated", "parkio.parking.spot"));
        existing.add(insertExistingRow("ParkingSession", "ParkingSessionStarted", "parkio.parking.session"));

        handler.handle(event);
        relay(liveBroker()).run();

        for (Existing row : existing) {
            assertThat(records(row.topic(), row.aggregateId(), Duration.ofSeconds(20))).as(row.eventType()).hasSize(1);
            assertThat(records(ERASURE_TOPIC, row.aggregateId(), Duration.ofSeconds(3))).as(row.eventType()).isEmpty();
        }
        assertThat(records(ERASURE_TOPIC, event.erasureRequestId(), Duration.ofSeconds(20))).hasSize(1);
    }

    @Test
    void noCopyOfTheErasedUserIdRemainsOutsideTombstoneAndAckOutbox() {
        UUID user = UUID.randomUUID();
        seed(user);

        handler.handle(request(user));

        assertThat(residue(user)).isEmpty();
    }

    @Test
    void trustSnapshotMovesToSentinelDerivedIdOrIsDroppedWhenSentinelHasOne() {
        String domain = "D-" + UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertTrustSnapshot(first, domain);
        insertTrustSnapshot(second, domain);

        handler.handle(request(first));
        handler.handle(request(second));

        assertThat(jdbc.queryForList("SELECT id FROM trust_snapshot WHERE trust_domain = ?", UUID.class, domain))
                .containsExactly(snapshotId(SENTINEL, domain));
        assertThat(jdbc.queryForObject("SELECT subject_id FROM trust_snapshot WHERE trust_domain = ?", UUID.class, domain))
                .isEqualTo(SENTINEL);
        assertThat(residue(first)).isEmpty();
        assertThat(residue(second)).isEmpty();
    }

    // U02 restore replay (docs/architecture/erasure-restore-replay-contract.md): the replayed erase and
    // its attempt-bound ACK use the same outbox; the restore ACK is keyed by the user.

    @Test
    void restoreReplayErasesAndPublishesTheAttemptBoundAck() throws Exception {
        UUID user = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        UserErasureRestoreReplayRequestedEvent replay = restoreReplay(user, UUID.randomUUID());
        seed(user);
        seed(bystander);
        Map<String, Long> bystanderBefore = userRows(bystander);

        handler.replayForRestore(replay);

        assertErased(user);
        assertThat(userRows(bystander)).isEqualTo(bystanderBefore);
        assertThat(restoreAckRows(user)).isEqualTo(1);
        relay(liveBroker()).run();
        List<ConsumerRecord<String, String>> published = records(ERASURE_TOPIC, user, Duration.ofSeconds(20));
        assertThat(published).hasSize(1);
        JsonNode envelope = objectMapper.readTree(published.get(0).value());
        assertThat(envelope.get("eventType").asText()).isEqualTo("UserErasureRestoreAcknowledged");
        assertThat(envelope.get("aggregateType").asText()).isEqualTo("AccountErasure");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(user.toString());
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("eventId").asText()).isEqualTo(AccountErasureHandler.restoreAckEventId(replay).toString());
        assertThat(payload.get("recoveryAttemptId").asText()).isEqualTo(replay.recoveryAttemptId().toString());
        assertThat(payload.get("restoredDatasetId").asText()).isEqualTo(replay.restoredDatasetId());
        assertThat(payload.get("erasureSetDigest").asText()).isEqualTo(replay.erasureSetDigest());
        assertThat(payload.get("authUserId").asText()).isEqualTo(user.toString());
        assertThat(payload.get("serviceName").asText()).isEqualTo("parking");
        assertThat(payload.get("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void restoreReplayCommitFailureLeavesNeitherTheEraseNorTheAck() {
        UUID user = UUID.randomUUID();
        seed(user);
        Map<String, Long> before = userRows(user);

        installCommitFailure();
        try {
            assertThatThrownBy(() -> handler.replayForRestore(restoreReplay(user, UUID.randomUUID())));
        } finally {
            dropCommitFailure();
        }

        assertThat(userRows(user)).isEqualTo(before);
        assertThat(tombstones(user)).isZero();
        assertThat(restoreAckRows(user)).isZero();
    }

    @Test
    void restoreReplayRedeliveryQueuesOneAckAndAnotherAttemptAFreshOne() {
        UUID user = UUID.randomUUID();
        seed(user);
        UserErasureRestoreReplayRequestedEvent replay = restoreReplay(user, UUID.randomUUID());

        handler.replayForRestore(replay);
        handler.replayForRestore(replay);
        assertThat(restoreAckRows(user)).isEqualTo(1);

        handler.replayForRestore(restoreReplay(user, UUID.randomUUID()));
        assertThat(restoreAckRows(user)).isEqualTo(2);
        assertErased(user);
    }

    /**
     * Every column, in any table, whose text form still contains the user id. By-design copies are
     * excluded: the tombstone (resurrection guard) and the outbox (the ACK payload carries
     * authUserId per the auth contract; retention cleanup purges published rows).
     */
    private List<String> residue(UUID user) {
        List<String> hits = new ArrayList<>();
        for (Map<String, Object> column : jdbc.queryForList("""
                SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND data_type IN ('uuid', 'text', 'character varying', 'json', 'jsonb')
                  AND table_name NOT IN ('erased_user_tombstones', 'outbox_events', 'flyway_schema_history')
                """)) {
            String table = (String) column.get("table_name");
            String name = (String) column.get("column_name");
            long rows = count("SELECT COUNT(*) FROM \"" + table + "\" WHERE strpos(\"" + name + "\"::text, ?) > 0",
                    user.toString());
            if (rows > 0) {
                hits.add(table + "." + name + "=" + rows);
            }
        }
        return hits;
    }

    private record Existing(UUID aggregateId, String eventType, String topic) {
    }

    /** An outbox row of an event type this service already published before U05. */
    private Existing insertExistingRow(String aggregateType, String eventType, String topic) {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, event_id, aggregate_type, aggregate_id, event_type, payload, occurred_at, published)
                VALUES (?, ?, ?, ?, ?, ?, now(), false)
                """, UUID.randomUUID(), eventId, aggregateType, aggregateId, eventType,
                "{\"eventId\":\"" + eventId + "\",\"synthetic\":true}");
        return new Existing(aggregateId, eventType, topic);
    }

    private void seed(UUID user) {
        UUID ownSpot = UUID.randomUUID();
        UUID otherSpot = UUID.randomUUID();
        insertSpot(ownSpot, user);
        insertSpot(otherSpot, UUID.randomUUID());
        UUID outcome = insertOutcome(ownSpot);
        jdbc.update("""
                INSERT INTO parking_sessions (id, user_id, status, parking_source, started_at, ended_at, latitude, longitude,
                    location, created_at, updated_at, completion_type, completion_reason)
                VALUES (?, ?, 'COMPLETED', 'MANUAL', now() - interval '1 hour', now(), 41.0, 29.0,
                    ST_SetSRID(ST_MakePoint(29.0, 41.0), 4326)::geography, now(), now(), 'MANUAL', 'MANUAL')
                """, UUID.randomUUID(), user);
        jdbc.update("""
                INSERT INTO parking_spot_search_logs (id, searcher_user_id, latitude, longitude, radius_meters, result_count)
                VALUES (?, ?, 41.0, 29.0, 500, 3)
                """, UUID.randomUUID(), user);
        jdbc.update("INSERT INTO parking_spot_view_logs (id, spot_id, viewer_user_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), otherSpot, user);
        jdbc.update("INSERT INTO parking_spot_verifications (id, spot_id, verifier_user_id, result) VALUES (?, ?, ?, 'AVAILABLE')",
                UUID.randomUUID(), otherSpot, user);
        jdbc.update("""
                INSERT INTO idempotency_records (id, user_id, http_method, operation_path, idempotency_key,
                    request_fingerprint, status, created_at, expires_at)
                VALUES (?, ?, 'POST', '/api/v1/parking/spots', ?, 'fp', 'COMPLETED', now(), now() + interval '1 day')
                """, UUID.randomUUID(), user, "key-" + UUID.randomUUID());
        String subjectJson = subjectJson(user);
        jdbc.update("""
                INSERT INTO trust_ledger (id, evaluation_id, subject_type, subject_id, trust_domain, trust_policy_version,
                    snapshot_schema_version, attribution_mapping_version, source_outcome_record_id, source_evidence_id,
                    source_evidence_group_id, evidence_type, contribution_role, attribution_quality, eligibility,
                    update_direction, trust_level, evaluated_at, evidence_json, previous_snapshot_json, evaluation_json)
                VALUES (?, ?, 'REPORTER', ?, 'SPOT_REPORTING', 'v1', 'v1', 'v1', ?, ?, ?, 'OUTCOME', 'REPORTER', 'DIRECT',
                    'ELIGIBLE', 'UP', 'MEDIUM', now(), ?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), user, outcome, UUID.randomUUID(), UUID.randomUUID(),
                subjectJson, subjectJson, subjectJson);
        insertTrustSnapshot(user, "SPOT_REPORTING");
        jdbc.update("""
                INSERT INTO fraud_evaluation_ledger (id, evaluation_id, subject_type, subject_id, fraud_domain, policy_version,
                    schema_version, mapping_version, aggregation_version, source_outcome_record_id, evidence_window_start,
                    evidence_window_end, risk_score, risk_band, confidence_band, effective_evidence_count, disposition,
                    decisive_rule, evaluated_at, evaluation_snapshot_json)
                VALUES (?, ?, 'USER', ?, 'SPOT_REPORTING', 'v1', 'v1', 'v1', 'v1', ?, now() - interval '1 day', now(),
                    100, 'LOW', 'HIGH', 1, 'NO_ACTION', 'rule', now(), ?)
                """, UUID.randomUUID(), UUID.randomUUID(), user, outcome, subjectJson);
        jdbc.update("""
                INSERT INTO pending_reward_ledger (id, evaluation_id, reward_subject_type, reward_subject_id, contribution_role,
                    source_outcome_record_id, source_contribution_id, source_parking_spot_id, evidence_group_id,
                    reward_policy_version, attribution_mapping_version, snapshot_schema_version, disposition, reward_unit,
                    calculated_amount, eligibility, primary_reason, outcome_classification, outcome_confidence_band,
                    evaluated_at, evidence_cutoff_at, contribution_json, evaluation_json)
                VALUES (?, ?, 'USER', ?, 'REPORTER', ?, ?, ?, ?, 'v1', 'v1', 'v1', 'PENDING', 'POINTS', 5, 'ELIGIBLE',
                    'CONFIRMED', 'CONFIRMED_CORRECT', 'HIGH', now(), now(), ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), user, outcome, UUID.randomUUID(), ownSpot, UUID.randomUUID(),
                subjectJson, subjectJson);
    }

    private static String subjectJson(UUID user) {
        return "{\"subject\":{\"type\":\"USER\",\"subjectId\":\"" + user + "\"},\"subjectId\":\"" + user + "\"}";
    }

    private void insertSpot(UUID id, UUID owner) {
        jdbc.update("""
                INSERT INTO parking_spots (id, owner_user_id, media_id, latitude, longitude, location,
                    suitable_vehicle_types, parking_context, legal_status, status, moderation_deadline_at)
                VALUES (?, ?, ?, 41.0, 29.0, ST_SetSRID(ST_MakePoint(29.0, 41.0), 4326)::geography,
                    'CAR', 'STREET', 'LEGAL', 'ACTIVE', now() + interval '1 day')
                """, id, owner, UUID.randomUUID());
    }

    private UUID insertOutcome(UUID spot) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outcome_history (id, evaluation_id, parking_spot_id, policy_version, snapshot_schema_version,
                    trigger_type, trigger_reference, evaluated_at, evidence_cutoff_at, classification, confidence,
                    primary_reason, validation_window_open, snapshot_json)
                VALUES (?, ?, ?, 'v1', 'v1', 'VERIFICATION', ?, now(), now(), 'CONFIRMED_CORRECT', 90, 'VERIFIED', false, '{}')
                """, id, UUID.randomUUID(), spot, UUID.randomUUID());
        return id;
    }

    /** Snapshot ids are derived from the subject, as TrustPersistenceMapper does. */
    private static UUID snapshotId(UUID subject, String domain) {
        return UUID.nameUUIDFromBytes(("trust-snapshot|REPORTER|" + subject + "|" + domain)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void insertTrustSnapshot(UUID user, String domain) {
        jdbc.update("""
                INSERT INTO trust_snapshot (id, subject_type, subject_id, trust_domain, trust_policy_version,
                    snapshot_schema_version, last_evaluated_at, snapshot_json)
                VALUES (?, 'REPORTER', ?, ?, 'v1', 'v1', now(), ?)
                """, snapshotId(user, domain), user, domain, subjectJson(user));
    }

    /** Rows that still identify the user, per erased column (deleted or anonymized). */
    private Map<String, Long> userRows(UUID user) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("parking_sessions.user_id", count("SELECT COUNT(*) FROM parking_sessions WHERE user_id = ?", user));
        counts.put("parking_spot_search_logs.searcher_user_id", count("SELECT COUNT(*) FROM parking_spot_search_logs WHERE searcher_user_id = ?", user));
        counts.put("parking_spot_view_logs.viewer_user_id", count("SELECT COUNT(*) FROM parking_spot_view_logs WHERE viewer_user_id = ?", user));
        counts.put("parking_spot_verifications.verifier_user_id", count("SELECT COUNT(*) FROM parking_spot_verifications WHERE verifier_user_id = ?", user));
        counts.put("idempotency_records.user_id", count("SELECT COUNT(*) FROM idempotency_records WHERE user_id = ?", user));
        counts.put("trust_snapshot.subject_id", count("SELECT COUNT(*) FROM trust_snapshot WHERE subject_id = ?", user));
        counts.put("trust_ledger.json", count("SELECT COUNT(*) FROM trust_ledger WHERE strpos(evidence_json || previous_snapshot_json || evaluation_json, CAST(? AS text)) > 0", user));
        counts.put("trust_snapshot.snapshot_json", count("SELECT COUNT(*) FROM trust_snapshot WHERE strpos(snapshot_json, CAST(? AS text)) > 0", user));
        counts.put("fraud_evaluation_ledger.json", count("SELECT COUNT(*) FROM fraud_evaluation_ledger WHERE strpos(evaluation_snapshot_json, CAST(? AS text)) > 0", user));
        counts.put("pending_reward_ledger.json", count("SELECT COUNT(*) FROM pending_reward_ledger WHERE strpos(contribution_json || evaluation_json, CAST(? AS text)) > 0", user));
        counts.put("parking_spots.owner_user_id", count("SELECT COUNT(*) FROM parking_spots WHERE owner_user_id = ?", user));
        counts.put("trust_ledger.subject_id", count("SELECT COUNT(*) FROM trust_ledger WHERE subject_id = ?", user));
        counts.put("fraud_evaluation_ledger.subject_id", count("SELECT COUNT(*) FROM fraud_evaluation_ledger WHERE subject_id = ?", user));
        counts.put("pending_reward_ledger.reward_subject_id", count("SELECT COUNT(*) FROM pending_reward_ledger WHERE reward_subject_id = ?", user));
        return counts;
    }

    /** Rows attributed to the erased-user sentinel, per anonymized column. */
    private Map<String, Long> sentinelRows() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("parking_spots.owner_user_id", count("SELECT COUNT(*) FROM parking_spots WHERE owner_user_id = ?", SENTINEL));
        counts.put("trust_ledger.subject_id", count("SELECT COUNT(*) FROM trust_ledger WHERE subject_id = ?", SENTINEL));
        counts.put("fraud_evaluation_ledger.subject_id", count("SELECT COUNT(*) FROM fraud_evaluation_ledger WHERE subject_id = ?", SENTINEL));
        counts.put("pending_reward_ledger.reward_subject_id", count("SELECT COUNT(*) FROM pending_reward_ledger WHERE reward_subject_id = ?", SENTINEL));
        return counts;
    }

    /** Total rows of tables whose rows are retained (anonymized, not deleted). */
    private Map<String, Long> retainedTotals() {
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("parking_spots", jdbc.queryForObject("SELECT COUNT(*) FROM parking_spots", Long.class));
        totals.put("trust_ledger", jdbc.queryForObject("SELECT COUNT(*) FROM trust_ledger", Long.class));
        totals.put("fraud_evaluation_ledger", jdbc.queryForObject("SELECT COUNT(*) FROM fraud_evaluation_ledger", Long.class));
        totals.put("pending_reward_ledger", jdbc.queryForObject("SELECT COUNT(*) FROM pending_reward_ledger", Long.class));
        return totals;
    }

    private void assertErased(UUID user) {
        assertThat(tombstones(user)).isEqualTo(1);
        assertThat(userRows(user)).allSatisfy((column, rows) -> assertThat(rows).as(column).isZero());
    }

    private long tombstones(UUID user) {
        return count("SELECT COUNT(*) FROM erased_user_tombstones WHERE auth_user_id = ?", user);
    }

    /** Deferred constraint trigger: the handler's own transaction fails at COMMIT. */
    private void installCommitFailure() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION u05_fail_commit() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'u05 injected commit failure'; END $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER u05_fail_commit AFTER INSERT ON erased_user_tombstones
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION u05_fail_commit()
                """);
    }

    private void dropCommitFailure() {
        jdbc.execute("DROP TRIGGER u05_fail_commit ON erased_user_tombstones");
    }

    private static UserErasureRequestedEvent request(UUID user) {
        return new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), user, Instant.now());
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long ackRows(UUID requestId) {
        return count("""
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND event_type = 'UserErasureAcknowledged'
                  AND aggregate_id = ?
                """, requestId);
    }

    private Map<String, Object> ackRow(UUID requestId) {
        return jdbc.queryForMap("""
                SELECT event_type, published, failure_count, dead_lettered FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND aggregate_id = ?
                """, requestId);
    }

    /** A relay instance whose poll runs inside a transaction, as the scheduled bean's does. */
    private Runnable relay(KafkaTemplate<String, Object> template) {
        ParkingOutboxRelay relay = new ParkingOutboxRelay(
                outbox, template, objectMapper, new SimpleMeterRegistry(), 100, 5000L, 10);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return () -> tx.executeWithoutResult(status -> relay.publishPending());
    }

    /** The first send to a not-yet-created topic can take seconds (metadata + auto-create). */
    private static KafkaTemplate<String, Object> liveBroker() {
        return brokerTemplate(KAFKA.getBootstrapServers(), 30_000);
    }

    /** Producer configured like the service's (application.yml): JSON envelope, no type headers. */
    private static KafkaTemplate<String, Object> brokerTemplate(String bootstrapServers, int maxBlockMs) {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    private static List<ConsumerRecord<String, String>> records(String topic, UUID key, Duration wait) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "u05-parking-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> matches = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.toString().equals(record.key())) {
                        matches.add(record);
                    }
                }
                if (!matches.isEmpty() && consumer.assignment().stream()
                        .allMatch(tp -> consumer.position(tp) >= consumer.endOffsets(List.of(tp)).get(tp))) {
                    break;
                }
            }
        }
        return matches;
    }

    private static UserErasureRestoreReplayRequestedEvent restoreReplay(UUID user, UUID attempt) {
        return new UserErasureRestoreReplayRequestedEvent(UUID.randomUUID(), attempt, "backup-stamp-2026-10-03",
                "e".repeat(64), user, Instant.parse("2026-09-29T08:16:00Z"), Instant.now());
    }

    private long restoreAckRows(UUID user) {
        return count("""
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND event_type = 'UserErasureRestoreAcknowledged'
                  AND aggregate_id = ?
                """, user);
    }
}
