package com.parkio.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.moderation.application.event.UserErasureRequestedEvent;
import com.parkio.moderation.application.event.UserErasureRestoreReplayRequestedEvent;
import com.parkio.moderation.infrastructure.messaging.ModerationOutboxRelay;
import com.parkio.moderation.infrastructure.persistence.jpa.OutboxEventJpaRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
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
 * U05 participant ACK outbox for moderation-service against real PostgreSQL (Flyway schema) and a real
 * Kafka broker, with synthetic data only. Proves that the SUCCESS ACK exists only as a committed
 * outbox row written with the local erase, that {@code ModerationOutboxRelay} publishes it to
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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

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

        // Broker unreachable: the send fails (the shared relay surfaces it synchronously and the
        // poll rolls back - U18), but the committed ACK row stays queued.
        assertThatThrownBy(relay(brokerTemplate("127.0.0.1:1", 2_000))::run);
        Map<String, Object> row = ackRow(event.erasureRequestId());
        assertThat(row.get("published")).isEqualTo(false);
        assertThat(row.get("dead_lettered")).isEqualTo(false);

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
        assertThat(payload.get("serviceName").asText()).isEqualTo("moderation");
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
        existing.add(insertExistingRow("ModerationCase", "ModerationCaseOpened", "parkio.moderation.case"));
        existing.add(insertExistingRow("User", "UserSuspended", "parkio.moderation.action"));

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
    void secondErasedUserIsFullyAnonymizedWhenSentinelAlreadyHoldsEquivalentRows() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        UUID spot = UUID.randomUUID();
        UUID sharedCase = UUID.randomUUID();
        insertCase(sharedCase, "PARKING_SPOT", spot, null, null);
        // Both users reported the same spot for the same reason and appealed the same case, and one
        // real reporter reported both users for the same reason: after erasure these rows coincide
        // on the sentinel, which the old NOT EXISTS guards skipped (leaving the user id behind).
        insertReport(first, "PARKING_SPOT", spot, "WRONG_LOCATION", sharedCase);
        insertReport(second, "PARKING_SPOT", spot, "WRONG_LOCATION", sharedCase);
        insertAppeal(first, sharedCase);
        insertAppeal(second, sharedCase);
        insertReport(reporter, "USER", first, "SPAM_BEHAVIOR", null);
        insertReport(reporter, "USER", second, "SPAM_BEHAVIOR", null);
        Map<String, Long> retainedBefore = retainedTotals();
        UserErasureRequestedEvent firstRequest = request(first);
        UserErasureRequestedEvent secondRequest = request(second);

        handler.handle(firstRequest);
        handler.handle(secondRequest);

        assertErased(first);
        assertErased(second);
        assertThat(retainedTotals()).isEqualTo(retainedBefore);
        assertThat(count("SELECT COUNT(*) FROM user_reports WHERE reporter_user_id = ? AND target_id = ?", SENTINEL, spot))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM appeals WHERE appeal_user_id = ? AND case_id = ?", SENTINEL, sharedCase))
                .isEqualTo(2);
        assertThat(count("""
                SELECT COUNT(*) FROM user_reports
                WHERE reporter_user_id = ? AND target_type = 'USER' AND target_id = ?
                """, reporter, SENTINEL)).isEqualTo(2);
        assertThat(ackRows(firstRequest.erasureRequestId())).isEqualTo(1);
        assertThat(ackRows(secondRequest.erasureRequestId())).isEqualTo(1);
    }

    @Test
    void realUsersStillCannotFileDuplicateReportsOrAppeals() {
        UUID reporter = UUID.randomUUID();
        UUID spot = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        insertCase(caseId, "PARKING_SPOT", spot, null, null);
        insertReport(reporter, "PARKING_SPOT", spot, "WRONG_LOCATION", caseId);
        insertAppeal(reporter, caseId);

        assertThatThrownBy(() -> insertReport(reporter, "PARKING_SPOT", spot, "WRONG_LOCATION", caseId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAppeal(reporter, caseId))
                .isInstanceOf(DataIntegrityViolationException.class);
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
        assertThat(payload.get("serviceName").asText()).isEqualTo("moderation");
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
        UUID spot = UUID.randomUUID();
        UUID ownedCase = UUID.randomUUID();
        UUID userCase = UUID.randomUUID();
        insertCase(ownedCase, "PARKING_SPOT", spot, user, null);
        insertCase(userCase, "USER", user, null, user);
        insertReport(user, "PARKING_SPOT", spot, "WRONG_LOCATION", ownedCase);
        insertReport(UUID.randomUUID(), "USER", user, "SPAM_BEHAVIOR", userCase);
        insertAppeal(user, ownedCase);
        jdbc.update("""
                INSERT INTO user_violations (id, user_id, case_id, reason, severity, action)
                VALUES (?, ?, ?, 'SPAM_BEHAVIOR', 'HIGH', 'SUSPEND_USER')
                """, UUID.randomUUID(), user, userCase);
    }

    private void insertCase(UUID id, String targetType, UUID targetId, UUID owner, UUID moderator) {
        jdbc.update("""
                INSERT INTO moderation_cases (id, target_type, target_id, reason, severity, status,
                    owner_user_id, assigned_moderator_id)
                VALUES (?, ?, ?, 'WRONG_LOCATION', 'MEDIUM', 'OPEN', ?, ?)
                """, id, targetType, targetId, owner, moderator);
    }

    private void insertReport(UUID reporter, String targetType, UUID targetId, String reason, UUID caseId) {
        jdbc.update("""
                INSERT INTO user_reports (id, reporter_user_id, target_type, target_id, reason, description, case_id)
                VALUES (?, ?, ?, ?, ?, 'synthetic', ?)
                """, UUID.randomUUID(), reporter, targetType, targetId, reason, caseId);
    }

    private void insertAppeal(UUID appellant, UUID caseId) {
        jdbc.update("INSERT INTO appeals (id, appeal_user_id, case_id, note, status) VALUES (?, ?, ?, 'synthetic', 'OPEN')",
                UUID.randomUUID(), appellant, caseId);
    }

    /** Rows that still identify the user, per erased column (deleted or anonymized). */
    private Map<String, Long> userRows(UUID user) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("user_reports.reporter_user_id", count("SELECT COUNT(*) FROM user_reports WHERE reporter_user_id = ?", user));
        counts.put("user_reports.target_id[USER]", count("SELECT COUNT(*) FROM user_reports WHERE target_type = 'USER' AND target_id = ?", user));
        counts.put("moderation_cases.owner_user_id", count("SELECT COUNT(*) FROM moderation_cases WHERE owner_user_id = ?", user));
        counts.put("moderation_cases.target_id[USER]", count("SELECT COUNT(*) FROM moderation_cases WHERE target_type = 'USER' AND target_id = ?", user));
        counts.put("moderation_cases.assigned_moderator_id", count("SELECT COUNT(*) FROM moderation_cases WHERE assigned_moderator_id = ?", user));
        counts.put("appeals.appeal_user_id", count("SELECT COUNT(*) FROM appeals WHERE appeal_user_id = ?", user));
        counts.put("user_violations.user_id", count("SELECT COUNT(*) FROM user_violations WHERE user_id = ?", user));
        return counts;
    }

    /** Rows attributed to the erased-user sentinel, per anonymized column. */
    private Map<String, Long> sentinelRows() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("user_reports.reporter_user_id", count("SELECT COUNT(*) FROM user_reports WHERE reporter_user_id = ?", SENTINEL));
        counts.put("user_reports.target_id[USER]", count("SELECT COUNT(*) FROM user_reports WHERE target_type = 'USER' AND target_id = ?", SENTINEL));
        counts.put("moderation_cases.owner_user_id", count("SELECT COUNT(*) FROM moderation_cases WHERE owner_user_id = ?", SENTINEL));
        counts.put("moderation_cases.target_id[USER]", count("SELECT COUNT(*) FROM moderation_cases WHERE target_type = 'USER' AND target_id = ?", SENTINEL));
        counts.put("moderation_cases.assigned_moderator_id", count("SELECT COUNT(*) FROM moderation_cases WHERE assigned_moderator_id = ?", SENTINEL));
        counts.put("appeals.appeal_user_id", count("SELECT COUNT(*) FROM appeals WHERE appeal_user_id = ?", SENTINEL));
        counts.put("user_violations.user_id", count("SELECT COUNT(*) FROM user_violations WHERE user_id = ?", SENTINEL));
        return counts;
    }

    /** Total rows of tables whose rows are retained (anonymized, not deleted). */
    private Map<String, Long> retainedTotals() {
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("user_reports", jdbc.queryForObject("SELECT COUNT(*) FROM user_reports", Long.class));
        totals.put("moderation_cases", jdbc.queryForObject("SELECT COUNT(*) FROM moderation_cases", Long.class));
        totals.put("appeals", jdbc.queryForObject("SELECT COUNT(*) FROM appeals", Long.class));
        totals.put("user_violations", jdbc.queryForObject("SELECT COUNT(*) FROM user_violations", Long.class));
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
        ModerationOutboxRelay relay = new ModerationOutboxRelay(
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
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "u05-moderation-it-" + UUID.randomUUID());
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
