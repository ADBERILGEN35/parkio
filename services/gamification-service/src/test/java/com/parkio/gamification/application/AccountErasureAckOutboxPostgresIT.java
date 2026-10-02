package com.parkio.gamification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gamification.application.event.UserErasureRequestedEvent;
import com.parkio.gamification.infrastructure.messaging.GamificationOutboxRelay;
import com.parkio.gamification.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * U05 participant ACK outbox, gamification pilot, against real PostgreSQL (Flyway schema) and a
 * real Kafka broker. Proves that the SUCCESS ACK exists only as a committed outbox row, that the
 * relay publishes it to {@code parkio.privacy.erasure} only from that row, that a publisher
 * failure after commit is retried by a fresh relay (restart), and that duplicate delivery of the
 * erase command is idempotent.
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
    void commitFailureLeavesNoAckIntentAndNothingIsPublished() {
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seedUserRows(user);
        installCommitFailure();
        try {
            assertThatThrownBy(() -> handler.handle(event));
        } finally {
            dropCommitFailure();
        }

        assertThat(count("SELECT COUNT(*) FROM erased_user_tombstones WHERE auth_user_id = ?", user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_level_progress WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(ackRows(event.erasureRequestId())).isZero();

        relay(brokerTemplate(KAFKA.getBootstrapServers())).run();
        assertThat(ackRecords(event.erasureRequestId(), Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void publisherFailureAfterCommitSurvivesRestartAndIsRetried() throws Exception {
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seedUserRows(user);

        handler.handle(event);
        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);

        // Broker unreachable: send() fails synchronously. The poll counts that failure for the
        // row and completes (U18 CL-F32); the committed ACK row stays queued, not dead-lettered.
        long failuresBefore = jdbc.queryForObject("SELECT COALESCE(SUM(failure_count), 0) FROM outbox_events", Long.class);
        Runnable brokenRelay = relay(brokerTemplate("127.0.0.1:1"));
        assertThatCode(brokenRelay::run).doesNotThrowAnyException();
        Map<String, Object> row = ackRow(event.erasureRequestId());
        assertThat(row.get("published")).isEqualTo(false);
        assertThat(row.get("dead_lettered")).isEqualTo(false);
        // The poll stops dispatching at the first throwing send() and counts that one row, which is
        // this ACK unless an earlier queued row comes first in the batch.
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(failure_count), 0) FROM outbox_events", Long.class))
                .as("exactly one synchronous send failure is counted").isEqualTo(failuresBefore + 1);

        // "Restart": a new relay instance over the same committed database row.
        relay(brokerTemplate(KAFKA.getBootstrapServers())).run();
        assertThat(ackRow(event.erasureRequestId()).get("published")).isEqualTo(true);

        List<ConsumerRecord<String, String>> records = ackRecords(event.erasureRequestId(), Duration.ofSeconds(20));
        assertThat(records).hasSize(1);
        JsonNode envelope = objectMapper.readTree(records.get(0).value());
        assertThat(envelope.get("eventType").asText()).isEqualTo("UserErasureAcknowledged");
        assertThat(envelope.get("aggregateType").asText()).isEqualTo("AccountErasure");
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("eventId").asText()).isEqualTo(envelope.get("eventId").asText());
        assertThat(payload.get("erasureRequestId").asText()).isEqualTo(event.erasureRequestId().toString());
        assertThat(payload.get("authUserId").asText()).isEqualTo(user.toString());
        assertThat(payload.get("serviceName").asText()).isEqualTo("gamification");
        assertThat(payload.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(payload.hasNonNull("occurredAt")).isTrue();
    }

    @Test
    void duplicateDeliveryQueuesOneAckAndKeepsErasedState() {
        UUID user = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seedUserRows(user);
        seedUserRows(bystander);

        handler.handle(event);
        handler.handle(event);
        relay(brokerTemplate(KAFKA.getBootstrapServers())).run();
        handler.handle(event);
        relay(brokerTemplate(KAFKA.getBootstrapServers())).run();

        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);
        assertThat(ackRecords(event.erasureRequestId(), Duration.ofSeconds(20))).hasSize(1);
        assertErased(user);
        assertThat(count("SELECT COUNT(*) FROM user_level_progress WHERE user_id = ?", bystander)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM trust_scores WHERE user_id = ?", bystander)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", bystander)).isEqualTo(1);
    }

    @Test
    void redeliveryAfterFailedCommitErasesAndQueuesAckOnce() {
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seedUserRows(user);
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
    void duplicateRequestStillErasesRowsWrittenAfterFirstAck() {
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        seedUserRows(user);
        handler.handle(event);
        seedUserRows(user); // e.g. a late event recreated user-keyed state

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

        handler.handle(event);
        handler.handle(replay);

        assertThat(ackRows(event.erasureRequestId())).isEqualTo(2);
        assertErased(user);
    }

    private void assertErased(UUID user) {
        assertThat(count("SELECT COUNT(*) FROM erased_user_tombstones WHERE auth_user_id = ?", user)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_level_progress WHERE user_id = ?", user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM trust_scores WHERE user_id = ?", user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?",
                AccountErasureHandler.ERASED_USER_SENTINEL)).isPositive();
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

    private void seedUserRows(UUID user) {
        jdbc.update("INSERT INTO user_level_progress (user_id, total_points) VALUES (?, 25)", user);
        jdbc.update("INSERT INTO trust_scores (user_id, score) VALUES (?, 90)", user);
        jdbc.update("""
                INSERT INTO point_transactions (id, user_id, idempotency_key, source_type, direction, points)
                VALUES (?, ?, ?, 'PARKING_VERIFIED', 'EARNED', 25)
                """, UUID.randomUUID(), user, "u05-" + UUID.randomUUID());
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
                SELECT published, failure_count, dead_lettered FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND aggregate_id = ?
                """, requestId);
    }

    /** A relay instance whose poll runs inside a transaction, as the scheduled bean's does. */
    private Runnable relay(KafkaTemplate<String, Object> template) {
        GamificationOutboxRelay relay = new GamificationOutboxRelay(
                outbox, template, objectMapper, new SimpleMeterRegistry(), 100, 5000L, 10);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return () -> tx.executeWithoutResult(status -> relay.publishPending());
    }

    /** Producer configured like the service's (application.yml): JSON envelope, no type headers. */
    private static KafkaTemplate<String, Object> brokerTemplate(String bootstrapServers) {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    private static List<ConsumerRecord<String, String>> ackRecords(UUID requestId, Duration wait) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "u05-it-" + UUID.randomUUID());
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
