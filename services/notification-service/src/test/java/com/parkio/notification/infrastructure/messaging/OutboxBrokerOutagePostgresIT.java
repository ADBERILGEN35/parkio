package com.parkio.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.notification.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.notification.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.notification.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * CL-N12 / B11: controlled broker outage futures must not dead-letter an erasure ACK; recovery
 * publishes exactly once; deterministic poison still exhausts max-attempts.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxBrokerOutagePostgresIT {

    private static final int MAX_ATTEMPTS = 3;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

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
    }

    @Autowired private OutboxEventJpaRepository outbox;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    @SuppressWarnings("unchecked")
    void transientBrokerFailuresDoNotDeadLetterAndRecoverExactlyOnce() {
        UUID aggregateId = queueAck("{\"n\":1}");
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new KafkaException("Broker not available"));
        CompletableFuture<SendResult<String, Object>> ok = CompletableFuture.completedFuture(null);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed, failed, ok);

        Runnable poll = relay(kafka);
        poll.run();
        poll.run();
        assertThat(row(aggregateId))
                .containsEntry("published", false)
                .containsEntry("failure_count", 0)
                .containsEntry("dead_lettered", false);

        poll.run();
        assertThat(row(aggregateId))
                .containsEntry("published", true)
                .containsEntry("failure_count", 0)
                .containsEntry("dead_lettered", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void poisonStillDeadLettersAfterMaxAttempts() {
        UUID poison = queueAck("{not json");
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        Runnable poll = relay(kafka);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            poll.run();
        }
        assertThat(row(poison))
                .containsEntry("published", false)
                .containsEntry("failure_count", MAX_ATTEMPTS)
                .containsEntry("dead_lettered", true);
    }

    private UUID queueAck(String payload) {
        UUID aggregateId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> outbox.save(new OutboxEventEntity(
                UUID.randomUUID(), UUID.randomUUID(), UserErasureAcknowledgedEvent.AGGREGATE_TYPE, aggregateId,
                UserErasureAcknowledgedEvent.TYPE, payload, Instant.parse("2026-10-15T09:00:00Z"), false)));
        return aggregateId;
    }

    private Runnable relay(KafkaTemplate<String, Object> kafka) {
        NotificationOutboxRelay relay = new NotificationOutboxRelay(
                outbox, kafka, objectMapper, new SimpleMeterRegistry(), 100, 5_000L, MAX_ATTEMPTS);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return () -> tx.executeWithoutResult(status -> relay.publishPending());
    }

    private Map<String, Object> row(UUID aggregateId) {
        return jdbc.queryForMap(
                "SELECT published, failure_count, dead_lettered FROM outbox_events WHERE aggregate_id = ?",
                aggregateId);
    }
}
