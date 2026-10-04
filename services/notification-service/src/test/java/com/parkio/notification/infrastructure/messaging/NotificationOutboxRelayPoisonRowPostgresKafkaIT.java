package com.parkio.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.notification.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.notification.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.notification.infrastructure.persistence.jpa.OutboxEventJpaRepository;
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
 * CL-F32 on real dependencies: one unreadable outbox row between valid ones, on a disposable
 * PostgreSQL and Kafka broker. The relay polls in a transaction, as the scheduled bean does. The
 * valid rows reach the topic exactly once, the poison row is dead-lettered after the configured
 * attempts and never published, and further polls send nothing again.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class NotificationOutboxRelayPoisonRowPostgresKafkaIT {

    private static final String ERASURE_TOPIC = "parkio.privacy.erasure";
    private static final int MAX_ATTEMPTS = 3;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired private OutboxEventJpaRepository outbox;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aPoisonRowIsDeadLetteredWhileTheValidRowsAreSentExactlyOnce() {
        UUID first = queue("{\"n\":1}");
        UUID poison = queue("{not json");
        UUID last = queue("{\"n\":3}");
        Runnable poll = relay();

        poll.run();

        assertThat(row(first)).containsEntry("published", true);
        assertThat(row(last)).containsEntry("published", true);
        assertThat(row(poison)).containsEntry("published", false).containsEntry("failure_count", 1)
                .containsEntry("dead_lettered", false);

        poll.run();
        poll.run();
        poll.run(); // the poison row is dead-lettered now; this poll finds nothing to send

        assertThat(row(poison)).containsEntry("published", false).containsEntry("failure_count", MAX_ATTEMPTS)
                .containsEntry("dead_lettered", true);
        assertThat(records(first)).hasSize(1);
        assertThat(records(last)).hasSize(1);
        assertThat(records(poison)).isEmpty();
    }

    /** Commits one erasure ACK outbox row with the given payload; returns its aggregate id. */
    private UUID queue(String payload) {
        UUID aggregateId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> outbox.save(new OutboxEventEntity(
                UUID.randomUUID(), UUID.randomUUID(), UserErasureAcknowledgedEvent.AGGREGATE_TYPE, aggregateId,
                UserErasureAcknowledgedEvent.TYPE, payload, Instant.now(), false)));
        return aggregateId;
    }

    private Map<String, Object> row(UUID aggregateId) {
        return jdbc.queryForMap(
                "SELECT published, failure_count, dead_lettered FROM outbox_events WHERE aggregate_id = ?", aggregateId);
    }

    /** A relay instance whose poll runs inside a transaction, as the scheduled bean's does. */
    private Runnable relay() {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 30_000,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        NotificationOutboxRelay relay = new NotificationOutboxRelay(outbox,
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props)), objectMapper, new SimpleMeterRegistry(),
                100, 30_000L, MAX_ATTEMPTS);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return () -> tx.executeWithoutResult(status -> relay.publishPending());
    }

    private static List<ConsumerRecord<String, String>> records(UUID key) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "cl-f32-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> matches = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(ERASURE_TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.toString().equals(record.key())) {
                        matches.add(record);
                    }
                }
                if (!consumer.assignment().isEmpty() && consumer.assignment().stream()
                        .allMatch(tp -> consumer.position(tp) >= consumer.endOffsets(List.of(tp)).get(tp))) {
                    break;
                }
            }
        }
        return matches;
    }
}
