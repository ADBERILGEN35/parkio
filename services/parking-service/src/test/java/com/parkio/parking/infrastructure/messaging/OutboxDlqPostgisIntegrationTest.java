package com.parkio.parking.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.parking.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
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
 * Integration test for the outbox DLQ / poison-row handling against a real PostgreSQL
 * (PostGIS) database. Unit tests for {@link ParkingOutboxRelay} mock the repository, so the
 * native claim query and the {@code dead_lettered} columns/migration are never exercised
 * against Postgres. This test closes that gap: it drives the real relay (with a partly-failing
 * Kafka producer) against the real schema and asserts the persisted DLQ state.
 *
 * <p>Verifies that a single poison row dead-letters after exhausting its attempts, that later
 * rows in the same batch still publish, and that the native
 * {@link OutboxEventJpaRepository#findUnpublishedBatchForUpdate(int)} claim query then skips
 * both the published and the dead-lettered row.
 *
 * <p>Tagged {@code integration}; runs only via {@code ./gradlew integrationTest} and is skipped
 * when Docker is unavailable.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class OutboxDlqPostgisIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_parking_dlq_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGIS::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        // Disable the scheduled relay bean so it cannot race the relay instance this test drives.
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired
    private OutboxEventJpaRepository outbox;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private TransactionTemplate tx;

    @BeforeEach
    void resetOutbox() {
        jdbc.update("DELETE FROM outbox_events");
        tx = new TransactionTemplate(txManager);
    }

    @Test
    @SuppressWarnings("unchecked")
    void poisonRowDeadLettersAfterMaxAttemptsAndLaterRowsStillPublish() {
        UUID poisonId = UUID.randomUUID();
        UUID healthyId = UUID.randomUUID();
        UUID poisonSpot = UUID.randomUUID();
        UUID healthySpot = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            outbox.save(row(poisonId, poisonSpot));
            outbox.save(row(healthyId, healthySpot));
        });

        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, Object> record = invocation.getArgument(0);
            if (poisonSpot.toString().equals(record.key())) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("simulated broker rejection"));
            }
            return CompletableFuture.completedFuture(mock(SendResult.class));
        });

        // maxAttempts = 1: the first failure dead-letters the poison row immediately, so the
        // assertion is deterministic with a single poll (no retry loops, no sleeps).
        ParkingOutboxRelay relay = new ParkingOutboxRelay(
                outbox, kafkaTemplate, objectMapper, new SimpleMeterRegistry(), 100, 5_000L, 1);

        // publishPending() is @Transactional in production; this manually-constructed instance
        // has no Spring proxy, so we supply the transaction the FOR UPDATE SKIP LOCKED claim needs.
        tx.executeWithoutResult(status -> relay.publishPending());

        OutboxEventEntity poison = outbox.findById(poisonId).orElseThrow();
        OutboxEventEntity healthy = outbox.findById(healthyId).orElseThrow();

        assertThat(healthy.isPublished()).isTrue();
        assertThat(healthy.isDeadLettered()).isFalse();
        assertThat(healthy.getFailureCount()).isZero();

        assertThat(poison.isPublished()).isFalse();
        assertThat(poison.isDeadLettered()).isTrue();
        assertThat(poison.getFailureCount()).isEqualTo(1);
        assertThat(poison.getLastFailureReason()).contains("simulated broker rejection");
        assertThat(poison.getLastFailedAt()).isNotNull();

        // The native claim query must now skip both the published and the dead-lettered row.
        List<OutboxEventEntity> claimable =
                tx.execute(status -> outbox.findUnpublishedBatchForUpdate(100));
        assertThat(claimable).isEmpty();

        assertThat(outbox.countByDeadLetteredTrue()).isEqualTo(1);
        assertThat(outbox.countByPublishedFalseAndDeadLetteredFalse()).isZero();
        assertThat(outbox.findOldestUnpublishedCreatedAt()).isNull();
    }

    private static OutboxEventEntity row(UUID id, UUID spotId) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-06-09T12:00:00Z");
        String payload = "{\"eventId\":\"" + eventId + "\",\"parkingSpotId\":\"" + spotId
                + "\",\"ownerUserId\":\"" + UUID.randomUUID() + "\",\"status\":\"ACTIVE\","
                + "\"occurredAt\":\"2026-06-09T12:00:00Z\"}";
        return new OutboxEventEntity(id, eventId, "ParkingSpot", spotId,
                "ParkingSpotCreated", payload, occurredAt, false);
    }
}
