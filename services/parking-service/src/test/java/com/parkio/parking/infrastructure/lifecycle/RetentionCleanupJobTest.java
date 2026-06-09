package com.parkio.parking.infrastructure.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RetentionCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:retention;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS outbox_events");
        jdbc.execute("DROP TABLE IF EXISTS inbox_events");
        jdbc.execute("""
                CREATE TABLE outbox_events (
                    id UUID PRIMARY KEY,
                    published BOOLEAN NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE inbox_events (
                    id UUID PRIMARY KEY,
                    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
    }

    @Test
    void outboxCleanupDeletesOnlyPublishedRowsOlderThanRetention() {
        UUID oldPublished = insertOutbox(true, NOW.minus(Duration.ofDays(8)));
        UUID oldUnpublished = insertOutbox(false, NOW.minus(Duration.ofDays(8)));
        UUID recentPublished = insertOutbox(true, NOW.minus(Duration.ofDays(6)));
        RetentionCleanupJob job = enabledJob();

        assertThat(job.cleanupOutbox()).isEqualTo(1);
        assertThat(ids("outbox_events")).containsExactlyInAnyOrder(oldUnpublished, recentPublished);
        assertThat(ids("outbox_events")).doesNotContain(oldPublished);
    }

    @Test
    void inboxCleanupDeletesOnlyRowsOlderThanRetention() {
        UUID oldProcessed = insertInbox(NOW.minus(Duration.ofDays(31)));
        UUID recentProcessed = insertInbox(NOW.minus(Duration.ofDays(29)));

        assertThat(enabledJob().cleanupInbox()).isEqualTo(1);
        assertThat(ids("inbox_events")).containsExactly(recentProcessed);
        assertThat(ids("inbox_events")).doesNotContain(oldProcessed);
    }

    @Test
    void disabledCleanupDoesNotDeleteRows() {
        UUID oldPublished = insertOutbox(true, NOW.minus(Duration.ofDays(8)));
        UUID oldProcessed = insertInbox(NOW.minus(Duration.ofDays(31)));
        RetentionCleanupJob job = new RetentionCleanupJob(
                jdbc, fixedClock(), false, false, Duration.ofDays(7), Duration.ofDays(30), 100);

        job.cleanup();

        assertThat(ids("outbox_events")).containsExactly(oldPublished);
        assertThat(ids("inbox_events")).containsExactly(oldProcessed);
    }

    private RetentionCleanupJob enabledJob() {
        return new RetentionCleanupJob(
                jdbc, fixedClock(), true, true, Duration.ofDays(7), Duration.ofDays(30), 100);
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private UUID insertOutbox(boolean published, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_events (id, published, created_at) VALUES (?, ?, ?)",
                id, published, createdAt);
        return id;
    }

    private UUID insertInbox(Instant processedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inbox_events (id, processed_at) VALUES (?, ?)", id, processedAt);
        return id;
    }

    private java.util.List<UUID> ids(String table) {
        return jdbc.query("SELECT id FROM " + table, (rs, rowNum) -> rs.getObject("id", UUID.class));
    }
}
