package com.parkio.auth.infrastructure.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class RetentionCleanupJobPostgresIT {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth_retention_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateSchema() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void clearTransportRows() {
        jdbc.update("DELETE FROM inbox_events");
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void outboxCleanupBindsCutoffAgainstTimestamptzAndPreservesProtectedRowsAndBatchLimit() {
        assertColumnIsTimestamptz("outbox_events", "created_at");
        UUID oldestPublished = insertOutbox(true, false, NOW.minus(Duration.ofDays(10)));
        UUID secondOldPublished = insertOutbox(true, false, NOW.minus(Duration.ofDays(9)));
        UUID thirdOldPublished = insertOutbox(true, false, NOW.minus(Duration.ofDays(8)));
        UUID recentPublished = insertOutbox(true, false, NOW.minus(Duration.ofDays(6)));
        UUID oldUnpublished = insertOutbox(false, false, NOW.minus(Duration.ofDays(10)));
        UUID openDeadLetter = insertOutbox(false, true, NOW.minus(Duration.ofDays(10)));

        int deleted = job(2).cleanupOutbox();

        assertThat(deleted).isEqualTo(2);
        assertThat(outboxIds())
                .containsExactlyInAnyOrder(
                        thirdOldPublished, recentPublished, oldUnpublished, openDeadLetter)
                .doesNotContain(oldestPublished, secondOldPublished);
    }

    @Test
    void inboxCleanupBindsCutoffAgainstTimestamptzAndRetainsRecentRowsAndHonorsBatchLimit() {
        assertColumnIsTimestamptz("inbox_events", "processed_at");
        UUID oldest = insertInbox(NOW.minus(Duration.ofDays(32)));
        UUID secondOldest = insertInbox(NOW.minus(Duration.ofDays(31)));
        UUID recent = insertInbox(NOW.minus(Duration.ofDays(29)));

        int deleted = job(1).cleanupInbox();

        assertThat(deleted).isEqualTo(1);
        assertThat(inboxIds()).containsExactlyInAnyOrder(secondOldest, recent).doesNotContain(oldest);
    }

    private RetentionCleanupJob job(int batchSize) {
        return new RetentionCleanupJob(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), true, true,
                Duration.ofDays(7), Duration.ofDays(30), batchSize);
    }

    private UUID insertOutbox(boolean published, boolean deadLettered, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (
                    id, aggregate_type, aggregate_id, event_type, payload,
                    occurred_at, published, created_at, dead_lettered
                ) VALUES (?, 'AuthUser', ?, 'RetentionTest', '{}', ?, ?, ?, ?)
                """, id, UUID.randomUUID(), Timestamp.from(createdAt), published,
                Timestamp.from(createdAt), deadLettered);
        return id;
    }

    private UUID insertInbox(Instant processedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inbox_events (id, event_type, processed_at) VALUES (?, ?, ?)",
                id, "RetentionTest", Timestamp.from(processedAt));
        return id;
    }

    private void assertColumnIsTimestamptz(String table, String column) {
        String dataType = jdbc.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
                """, String.class, table, column);
        assertThat(dataType).isEqualTo("timestamp with time zone");
    }

    private List<UUID> outboxIds() {
        return jdbc.query("SELECT id FROM outbox_events",
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    private List<UUID> inboxIds() {
        return jdbc.query("SELECT id FROM inbox_events",
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }
}
