package com.parkio.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.user.application.event.UserErasureRequestedEvent;
import com.parkio.user.infrastructure.messaging.UserOutboxRelay;
import com.parkio.user.infrastructure.persistence.jpa.OutboxEventJpaRepository;
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
 * U05 participant ACK outbox for user-service, against real PostgreSQL (Flyway schema) and a real
 * Kafka broker, with synthetic data only. Proves that the SUCCESS ACK exists only as a committed
 * outbox row written with the profile-graph erase, that the relay publishes it to
 * {@code parkio.privacy.erasure} only from that row, that a broker outage after commit leaves it
 * queued for a later relay, and that duplicate/replayed delivery follows the shared contract
 * (docs/architecture/erasure-ack-outbox-contract.md).
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AccountErasureAckOutboxPostgresIT {

    private static final String ERASURE_TOPIC = "parkio.privacy.erasure";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

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
        seedProfileGraph(user);
        Map<String, Long> before = graphCounts(user);

        installCommitFailure();
        try {
            assertThatThrownBy(() -> handler.handle(event));
        } finally {
            dropCommitFailure();
        }

        assertThat(graphCounts(user)).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM erased_user_tombstones WHERE auth_user_id = ?", user)).isZero();
        assertThat(ackRows(event.erasureRequestId())).isZero();

        relay(brokerTemplate(KAFKA.getBootstrapServers(), 30_000)).run();
        assertThat(ackRecords(event.erasureRequestId(), Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void successfulEraseCommitsTogetherWithAckRow() {
        UUID user = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seedProfileGraph(user);
        seedProfileGraph(bystander);
        Map<String, Long> bystanderBefore = graphCounts(bystander);

        handler.handle(event);

        assertErased(user);
        Map<String, Object> row = ackRow(event.erasureRequestId());
        assertThat(row.get("published")).isEqualTo(false);
        assertThat(row.get("event_type")).isEqualTo("UserErasureAcknowledged");
        assertThat(graphCounts(bystander)).isEqualTo(bystanderBefore);
        assertThat(count("SELECT COUNT(*) FROM erased_user_tombstones WHERE auth_user_id = ?", bystander)).isZero();
    }

    @Test
    void brokerOutageAfterCommitKeepsAckQueuedForLaterDelivery() throws Exception {
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seedProfileGraph(user);
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
        relay(brokerTemplate(KAFKA.getBootstrapServers(), 30_000)).run();
        assertThat(ackRow(event.erasureRequestId()).get("published")).isEqualTo(true);

        List<ConsumerRecord<String, String>> records = ackRecords(event.erasureRequestId(), Duration.ofSeconds(20));
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
        assertThat(payload.get("serviceName").asText()).isEqualTo("user");
        assertThat(payload.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(payload.hasNonNull("occurredAt")).isTrue();
    }

    @Test
    void duplicateDeliveryQueuesOneAckAndKeepsErasedState() {
        UUID user = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seedProfileGraph(user);
        seedProfileGraph(bystander);
        Map<String, Long> bystanderBefore = graphCounts(bystander);

        handler.handle(event);
        handler.handle(event);
        relay(brokerTemplate(KAFKA.getBootstrapServers(), 30_000)).run();
        handler.handle(event);
        relay(brokerTemplate(KAFKA.getBootstrapServers(), 30_000)).run();

        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);
        assertThat(ackRecords(event.erasureRequestId(), Duration.ofSeconds(20))).hasSize(1);
        assertErased(user);
        assertThat(graphCounts(bystander)).isEqualTo(bystanderBefore);
    }

    @Test
    void redeliveryAfterFailedCommitErasesAndQueuesAckOnce() {
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seedProfileGraph(user);
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
        seedProfileGraph(user);
        handler.handle(event);
        seedProfileGraph(user); // synthetic: user-keyed state reappears after the first ACK

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
        seedProfileGraph(user);

        handler.handle(event);
        handler.handle(replay);

        assertErased(user);
        assertThat(jdbc.queryForList("""
                SELECT event_id FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND aggregate_id = ?
                """, UUID.class, event.erasureRequestId()))
                .containsExactlyInAnyOrder(AccountErasureHandler.ackEventId(event), AccountErasureHandler.ackEventId(replay));
    }

    private void assertErased(UUID user) {
        assertThat(count("SELECT COUNT(*) FROM erased_user_tombstones WHERE auth_user_id = ?", user)).isEqualTo(1);
        assertThat(graphCounts(user)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
    }

    /** Rows of the user's profile graph, keyed by table; profile-scoped tables join through the profile. */
    private Map<String, Long> graphCounts(UUID user) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("user_profiles", count("SELECT COUNT(*) FROM user_profiles WHERE auth_user_id = ?", user));
        counts.put("pending_user_status_events",
                count("SELECT COUNT(*) FROM pending_user_status_events WHERE auth_user_id = ?", user));
        for (String table : List.of("user_preferences", "user_vehicle_profiles", "user_trust_profiles",
                "user_trust_score_history", "saved_places")) {
            counts.put(table, count("SELECT COUNT(*) FROM " + table + " t JOIN user_profiles p ON p.id = t.user_profile_id"
                    + " WHERE p.auth_user_id = ?", user));
        }
        return counts;
    }

    private void seedProfileGraph(UUID user) {
        UUID profile = UUID.randomUUID();
        jdbc.update("INSERT INTO user_profiles (id, auth_user_id, display_name, status) VALUES (?, ?, 'Synthetic', 'ACTIVE')",
                profile, user);
        jdbc.update("INSERT INTO user_preferences (id, user_profile_id) VALUES (?, ?)", UUID.randomUUID(), profile);
        jdbc.update("INSERT INTO user_vehicle_profiles (id, user_profile_id, vehicle_type, plate) VALUES (?, ?, 'SEDAN', '34TST01')",
                UUID.randomUUID(), profile);
        jdbc.update("INSERT INTO user_trust_profiles (id, user_profile_id) VALUES (?, ?)", UUID.randomUUID(), profile);
        jdbc.update("""
                INSERT INTO user_trust_score_history (id, user_profile_id, new_score, reason, occurred_at)
                VALUES (?, ?, 90, 'SYNTHETIC', now())
                """, UUID.randomUUID(), profile);
        jdbc.update("""
                INSERT INTO saved_places (id, user_profile_id, kind, latitude, longitude, source, created_at, updated_at)
                VALUES (?, ?, 'HOME', 41.0, 29.0, 'MAP_PIN', now(), now())
                """, UUID.randomUUID(), profile);
        jdbc.update("""
                INSERT INTO pending_user_status_events (id, auth_user_id, target_status, occurred_at)
                VALUES (?, ?, 'SUSPENDED', now())
                """, UUID.randomUUID(), user);
        assertThat(graphCounts(user)).allSatisfy((table, rows) -> assertThat(rows).as(table).isEqualTo(1));
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

    private long count(String sql, UUID id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }

    private long ackRows(UUID requestId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND event_type = 'UserErasureAcknowledged'
                  AND aggregate_id = ?
                """, Long.class, requestId);
    }

    private Map<String, Object> ackRow(UUID requestId) {
        return jdbc.queryForMap("""
                SELECT event_type, published, failure_count, dead_lettered FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND aggregate_id = ?
                """, requestId);
    }

    /** A relay instance whose poll runs inside a transaction, as the scheduled bean's does. */
    private Runnable relay(KafkaTemplate<String, Object> template) {
        UserOutboxRelay relay = new UserOutboxRelay(
                outbox, template, objectMapper, new SimpleMeterRegistry(), 100, 5000L, 10);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return () -> tx.executeWithoutResult(status -> relay.publishPending());
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

    private static List<ConsumerRecord<String, String>> ackRecords(UUID requestId, Duration wait) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "u05-user-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> matches = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(ERASURE_TOPIC));
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (requestId.toString().equals(record.key())) {
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
}
