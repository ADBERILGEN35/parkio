package com.parkio.parking.infrastructure.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
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
        jdbc.execute("DROP TABLE IF EXISTS parking_spot_search_logs");
        jdbc.execute("DROP TABLE IF EXISTS parking_spot_view_logs");
        jdbc.execute("""
                CREATE TABLE outbox_events (
                    id UUID PRIMARY KEY,
                    published BOOLEAN NOT NULL,
                    dead_lettered BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE inbox_events (
                    id UUID PRIMARY KEY,
                    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE parking_spot_search_logs (
                    id UUID PRIMARY KEY,
                    searcher_user_id UUID NOT NULL,
                    latitude DOUBLE PRECISION NOT NULL,
                    longitude DOUBLE PRECISION NOT NULL,
                    radius_meters DOUBLE PRECISION NOT NULL,
                    result_count INTEGER NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE parking_spot_view_logs (
                    id UUID PRIMARY KEY,
                    spot_id UUID NOT NULL,
                    viewer_user_id UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
    }

    @Test
    void outboxCleanupDeletesOnlyPublishedRowsOlderThanRetention() {
        UUID oldPublished = insertOutbox(true, NOW.minus(Duration.ofDays(8)));
        UUID oldUnpublished = insertOutbox(false, NOW.minus(Duration.ofDays(8)));
        UUID oldDeadLetter = insertOutbox(false, true, NOW.minus(Duration.ofDays(8)));
        UUID recentPublished = insertOutbox(true, NOW.minus(Duration.ofDays(6)));
        RetentionCleanupJob job = enabledJob();

        assertThat(job.cleanupOutbox()).isEqualTo(1);
        assertThat(ids("outbox_events"))
                .containsExactlyInAnyOrder(oldUnpublished, oldDeadLetter, recentPublished);
        assertThat(ids("outbox_events")).doesNotContain(oldPublished);
    }

    @Test
    void outboxCleanupDeletesTheOldestEligibleRowsUpToTheBatchLimit() {
        UUID oldest = insertOutbox(true, NOW.minus(Duration.ofDays(10)));
        UUID secondOldest = insertOutbox(true, NOW.minus(Duration.ofDays(9)));
        UUID recent = insertOutbox(true, NOW.minus(Duration.ofDays(6)));
        RetentionCleanupJob job = new RetentionCleanupJob(
                jdbc, fixedClock(), true, true, true, Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofDays(30), 1);

        assertThat(job.cleanupOutbox()).isEqualTo(1);
        assertThat(ids("outbox_events")).containsExactlyInAnyOrder(secondOldest, recent);
        assertThat(ids("outbox_events")).doesNotContain(oldest);
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
    void inboxCleanupHonorsTheBatchLimit() {
        UUID oldest = insertInbox(NOW.minus(Duration.ofDays(32)));
        UUID secondOldest = insertInbox(NOW.minus(Duration.ofDays(31)));
        UUID recent = insertInbox(NOW.minus(Duration.ofDays(29)));
        RetentionCleanupJob job = new RetentionCleanupJob(
                jdbc, fixedClock(), true, true, true, Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofDays(30), 1);

        assertThat(job.cleanupInbox()).isEqualTo(1);
        assertThat(ids("inbox_events")).containsExactlyInAnyOrder(secondOldest, recent);
        assertThat(ids("inbox_events")).doesNotContain(oldest);
    }

    @Test
    void disabledCleanupDoesNotDeleteRows() {
        UUID oldPublished = insertOutbox(true, NOW.minus(Duration.ofDays(8)));
        UUID oldProcessed = insertInbox(NOW.minus(Duration.ofDays(31)));
        UUID oldSearch = insertSearchLog(NOW.minus(Duration.ofDays(31)));
        UUID oldView = insertViewLog(NOW.minus(Duration.ofDays(31)));
        RetentionCleanupJob job = new RetentionCleanupJob(
                jdbc, fixedClock(), false, false, false, Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofDays(30), 100);

        job.cleanup();

        assertThat(ids("outbox_events")).containsExactly(oldPublished);
        assertThat(ids("inbox_events")).containsExactly(oldProcessed);
        assertThat(ids("parking_spot_search_logs")).containsExactly(oldSearch);
        assertThat(ids("parking_spot_view_logs")).containsExactly(oldView);
    }

    // CL-F17 / PRIV-002: per-user location logs.

    @Test
    void locationLogCleanupDeletesOnlyRowsOlderThanTheRetention() {
        UUID oldSearch = insertSearchLog(NOW.minus(Duration.ofDays(31)));
        UUID recentSearch = insertSearchLog(NOW.minus(Duration.ofDays(29)));
        UUID oldView = insertViewLog(NOW.minus(Duration.ofDays(31)));
        UUID recentView = insertViewLog(NOW.minus(Duration.ofDays(29)));
        RetentionCleanupJob job = enabledJob();

        assertThat(job.cleanupSearchLogs()).isEqualTo(1);
        assertThat(job.cleanupViewLogs()).isEqualTo(1);
        assertThat(ids("parking_spot_search_logs")).containsExactly(recentSearch);
        assertThat(ids("parking_spot_search_logs")).doesNotContain(oldSearch);
        assertThat(ids("parking_spot_view_logs")).containsExactly(recentView);
        assertThat(ids("parking_spot_view_logs")).doesNotContain(oldView);
    }

    @Test
    void locationLogCleanupDeletesTheOldestRowsUpToTheBatchLimit() {
        UUID oldestSearch = insertSearchLog(NOW.minus(Duration.ofDays(40)));
        UUID olderSearch = insertSearchLog(NOW.minus(Duration.ofDays(35)));
        UUID recentSearch = insertSearchLog(NOW.minus(Duration.ofDays(10)));
        UUID oldestView = insertViewLog(NOW.minus(Duration.ofDays(40)));
        UUID olderView = insertViewLog(NOW.minus(Duration.ofDays(35)));
        RetentionCleanupJob job = new RetentionCleanupJob(
                jdbc, fixedClock(), true, true, true, Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofDays(30), 1);

        assertThat(job.cleanupSearchLogs()).isEqualTo(1);
        assertThat(ids("parking_spot_search_logs")).containsExactlyInAnyOrder(olderSearch, recentSearch);
        assertThat(ids("parking_spot_search_logs")).doesNotContain(oldestSearch);
        assertThat(job.cleanupViewLogs()).isEqualTo(1);
        assertThat(ids("parking_spot_view_logs")).containsExactly(olderView);
        assertThat(ids("parking_spot_view_logs")).doesNotContain(oldestView);
        assertThat(job.cleanupSearchLogs()).isEqualTo(1);
        assertThat(ids("parking_spot_search_logs")).containsExactly(recentSearch);
    }

    @Test
    void locationLogCleanupUsesItsOwnRetentionNotTheInboxOne() {
        UUID search = insertSearchLog(NOW.minus(Duration.ofDays(20)));
        UUID view = insertViewLog(NOW.minus(Duration.ofDays(20)));
        RetentionCleanupJob job = new RetentionCleanupJob(
                jdbc, fixedClock(), true, true, true, Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofDays(14), 100);

        job.cleanup();

        assertThat(ids("parking_spot_search_logs")).doesNotContain(search);
        assertThat(ids("parking_spot_view_logs")).doesNotContain(view);
    }

    @Test
    void locationLogCleanupDisabledLeavesOldRowsWhileTransportCleanupRuns() {
        UUID oldPublished = insertOutbox(true, NOW.minus(Duration.ofDays(8)));
        UUID oldSearch = insertSearchLog(NOW.minus(Duration.ofDays(400)));
        UUID oldView = insertViewLog(NOW.minus(Duration.ofDays(400)));
        RetentionCleanupJob job = new RetentionCleanupJob(
                jdbc, fixedClock(), true, true, false, Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofDays(30), 100);

        job.cleanup();

        assertThat(ids("outbox_events")).doesNotContain(oldPublished);
        assertThat(ids("parking_spot_search_logs")).containsExactly(oldSearch);
        assertThat(ids("parking_spot_view_logs")).containsExactly(oldView);
    }

    @Test
    void aNonPositiveLocationLogRetentionIsRefusedAtConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RetentionCleanupJob(
                        jdbc, fixedClock(), true, true, true, Duration.ofDays(7), Duration.ofDays(30),
                        Duration.ZERO, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("location-log-retention");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RetentionCleanupJob(
                        jdbc, fixedClock(), true, true, true, Duration.ofDays(7), Duration.ofDays(30),
                        Duration.ofDays(-1), 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RetentionCleanupJob enabledJob() {
        return new RetentionCleanupJob(
                jdbc, fixedClock(), true, true, true, Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofDays(30), 100);
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private UUID insertOutbox(boolean published, Instant createdAt) {
        return insertOutbox(published, false, createdAt);
    }

    private UUID insertOutbox(boolean published, boolean deadLettered, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, published, dead_lettered, created_at)
                VALUES (?, ?, ?, ?)
                """, id, published, deadLettered, Timestamp.from(createdAt));
        return id;
    }

    private UUID insertInbox(Instant processedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inbox_events (id, processed_at) VALUES (?, ?)",
                id, Timestamp.from(processedAt));
        return id;
    }

    private UUID insertSearchLog(Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO parking_spot_search_logs
                    (id, searcher_user_id, latitude, longitude, radius_meters, result_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, UUID.randomUUID(), 38.42, 27.14, 500.0, 3, Timestamp.from(createdAt));
        return id;
    }

    private UUID insertViewLog(Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO parking_spot_view_logs (id, spot_id, viewer_user_id, created_at)
                VALUES (?, ?, ?, ?)
                """, id, UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(createdAt));
        return id;
    }

    private java.util.List<UUID> ids(String table) {
        return jdbc.query("SELECT id FROM " + table, (rs, rowNum) -> rs.getObject("id", UUID.class));
    }
}
