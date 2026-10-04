package com.parkio.gamification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gamification.application.event.UserErasureRestoreReplayRequestedEvent;
import com.parkio.gamification.infrastructure.messaging.GamificationOutboxRelay;
import com.parkio.gamification.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
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
 * U02 restore replay, gamification pilot, against real PostgreSQL (Flyway schema) and a real
 * Kafka broker: the replay runs the live erase and queues an attempt-bound restore ACK in the same
 * transaction; the relay publishes that ACK to {@code parkio.privacy.erasure}; a failed commit
 * leaves neither; a redelivery queues one ACK and another attempt a fresh one.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AccountErasureRestoreReplayPostgresIT {

    private static final String ERASURE_TOPIC = "parkio.privacy.erasure";
    private static final List<String> TOPICS = List.of(ERASURE_TOPIC);
    private static final String ACK_TYPE = "UserErasureRestoreAcknowledged";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    /**
     * The topics these tests publish to exist before the first send (#185 review N8). On a fresh
     * broker the first send otherwise waits for topic auto-creation, which can outlast the
     * producer's max.block.ms on a loaded host; the relay then records a failure and the test
     * finds no record.
     */
    @BeforeAll
    static void createTopics() throws Exception {
        try (Admin admin = Admin.create(
                Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            Set<String> existing = admin.listTopics().names().get(60, TimeUnit.SECONDS);
            List<NewTopic> missing = TOPICS.stream()
                    .filter(topic -> !existing.contains(topic))
                    .map(topic -> new NewTopic(topic, 1, (short) 1))
                    .toList();
            admin.createTopics(missing).all().get(60, TimeUnit.SECONDS);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.privacy.restore-replay.enabled", () -> "true");
    }

    @Autowired private AccountErasureHandler handler;
    @Autowired private OutboxEventJpaRepository outbox;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theReplayErasesTheUserAndTheRelayPublishesTheAttemptBoundAck() throws Exception {
        UUID user = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        UserErasureRestoreReplayRequestedEvent event = replay(user, UUID.randomUUID());
        seedUserRows(user);
        seedUserRows(bystander);

        handler.replayForRestore(event);

        assertErased(user);
        assertThat(count("SELECT COUNT(*) FROM user_level_progress WHERE user_id = ?", bystander)).isEqualTo(1);
        assertThat(ackRows(user)).isEqualTo(1);

        relay().run();
        List<ConsumerRecord<String, String>> records = ackRecords(user, Duration.ofSeconds(20));
        assertThat(records).hasSize(1);
        JsonNode envelope = objectMapper.readTree(records.get(0).value());
        assertThat(envelope.get("eventType").asText()).isEqualTo(ACK_TYPE);
        assertThat(envelope.get("aggregateType").asText()).isEqualTo("AccountErasure");
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("eventId").asText()).isEqualTo(envelope.get("eventId").asText());
        assertThat(payload.get("recoveryAttemptId").asText()).isEqualTo(event.recoveryAttemptId().toString());
        assertThat(payload.get("restoredDatasetId").asText()).isEqualTo(event.restoredDatasetId());
        assertThat(payload.get("erasureSetDigest").asText()).isEqualTo(event.erasureSetDigest());
        assertThat(payload.get("authUserId").asText()).isEqualTo(user.toString());
        assertThat(payload.get("serviceName").asText()).isEqualTo("gamification");
        assertThat(payload.get("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void aFailedCommitLeavesNeitherTheEraseNorTheAck() {
        UUID user = UUID.randomUUID();
        seedUserRows(user);
        installCommitFailure();
        try {
            assertThatThrownBy(() -> handler.replayForRestore(replay(user, UUID.randomUUID())));
        } finally {
            dropCommitFailure();
        }

        assertThat(count("SELECT COUNT(*) FROM erased_user_tombstones WHERE auth_user_id = ?", user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_level_progress WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(ackRows(user)).isZero();
    }

    @Test
    void aRedeliveryQueuesOneAckAndAnotherAttemptAFreshOne() {
        UUID user = UUID.randomUUID();
        seedUserRows(user);
        UserErasureRestoreReplayRequestedEvent event = replay(user, UUID.randomUUID());

        handler.replayForRestore(event);
        handler.replayForRestore(event);
        assertThat(ackRows(user)).isEqualTo(1);

        handler.replayForRestore(replay(user, UUID.randomUUID()));
        assertThat(ackRows(user)).isEqualTo(2);
        assertErased(user);
    }

    private void assertErased(UUID user) {
        assertThat(count("SELECT COUNT(*) FROM erased_user_tombstones WHERE auth_user_id = ?", user)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_level_progress WHERE user_id = ?", user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM trust_scores WHERE user_id = ?", user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", user)).isZero();
    }

    /** Deferred constraint trigger: the handler's own transaction fails at COMMIT. */
    private void installCommitFailure() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION u02_restore_fail_commit() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'u02 injected commit failure'; END $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER u02_restore_fail_commit AFTER INSERT ON erased_user_tombstones
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION u02_restore_fail_commit()
                """);
    }

    private void dropCommitFailure() {
        jdbc.execute("DROP TRIGGER u02_restore_fail_commit ON erased_user_tombstones");
    }

    private static UserErasureRestoreReplayRequestedEvent replay(UUID user, UUID attempt) {
        return new UserErasureRestoreReplayRequestedEvent(UUID.randomUUID(), attempt, "backup-stamp-2026-10-03",
                "c".repeat(64), user, Instant.parse("2026-09-29T08:16:00Z"), Instant.now());
    }

    private void seedUserRows(UUID user) {
        jdbc.update("INSERT INTO user_level_progress (user_id, total_points) VALUES (?, 25)", user);
        jdbc.update("INSERT INTO trust_scores (user_id, score) VALUES (?, 90)", user);
        jdbc.update("""
                INSERT INTO point_transactions (id, user_id, idempotency_key, source_type, direction, points)
                VALUES (?, ?, ?, 'PARKING_VERIFIED', 'EARNED', 25)
                """, UUID.randomUUID(), user, "u02-" + UUID.randomUUID());
    }

    private long count(String sql, UUID id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }

    private long ackRows(UUID user) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND event_type = ? AND aggregate_id = ?
                """, Long.class, ACK_TYPE, user);
    }

    /** A relay instance whose poll runs inside a transaction, as the scheduled bean's does. */
    private Runnable relay() {
        GamificationOutboxRelay relay = new GamificationOutboxRelay(
                outbox, brokerTemplate(), objectMapper, new SimpleMeterRegistry(), 100, 5000L, 10);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return () -> tx.executeWithoutResult(status -> relay.publishPending());
    }

    /** Producer configured like the service's (application.yml): JSON envelope, no type headers. */
    private static KafkaTemplate<String, Object> brokerTemplate() {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                // The first send creates the topic, which can take longer than a couple of seconds.
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 30000,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    private static List<ConsumerRecord<String, String>> ackRecords(UUID user, Duration wait) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "u02-restore-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> matches = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(ERASURE_TOPIC));
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (user.toString().equals(record.key())) {
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
