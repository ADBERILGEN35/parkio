package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.infrastructure.lifecycle.RetentionCleanupJob;
import com.parkio.parking.testsupport.PostgisTestImages;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * CL-F17 / PRIV-002 on real PostgreSQL: Flyway V42 adds the created_at indexes, and the retention
 * cleanup deletes only location-log rows older than the configured period, oldest first, in bounded
 * batches, while account erasure keeps deleting a user's rows regardless of age.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class LocationLogRetentionPostgisIT {

    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_parking_location_log_retention_it")
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
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.kafka.ai-validation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        // The scheduled bean stays off; the tests drive their own instances below.
        registry.add("parkio.lifecycle.retention.location-logs-enabled", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM parking_spot_search_logs");
        jdbc.update("DELETE FROM parking_spot_view_logs");
    }

    @Test
    void flywayAddsTheCreatedAtIndexesForBothLocationLogTables() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() "
                        + "AND indexname IN ('idx_parking_spot_search_logs_created_at', 'idx_parking_spot_view_logs_created_at') "
                        + "ORDER BY indexname",
                String.class);
        assertThat(indexes).containsExactly(
                "idx_parking_spot_search_logs_created_at", "idx_parking_spot_view_logs_created_at");
    }

    @Test
    void cleanupDeletesOnlyRowsOlderThanTheRetentionOldestFirstInBoundedBatches() {
        UUID oldestSearch = insertSearchLog(UUID.randomUUID(), NOW.minus(Duration.ofDays(45)));
        UUID oldSearch = insertSearchLog(UUID.randomUUID(), NOW.minus(Duration.ofDays(31)));
        UUID recentSearch = insertSearchLog(UUID.randomUUID(), NOW.minus(Duration.ofDays(29)));
        UUID oldestView = insertViewLog(UUID.randomUUID(), NOW.minus(Duration.ofDays(45)));
        UUID oldView = insertViewLog(UUID.randomUUID(), NOW.minus(Duration.ofDays(31)));
        UUID recentView = insertViewLog(UUID.randomUUID(), NOW.minus(Duration.ofDays(29)));
        RetentionCleanupJob job = new RetentionCleanupJob(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), false, false, true,
                Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(30), 1);

        assertThat(job.cleanupSearchLogs()).isEqualTo(1);
        assertThat(ids("parking_spot_search_logs")).containsExactlyInAnyOrder(oldSearch, recentSearch);
        assertThat(job.cleanupSearchLogs()).isEqualTo(1);
        assertThat(ids("parking_spot_search_logs")).containsExactly(recentSearch);
        assertThat(job.cleanupSearchLogs()).isZero();

        assertThat(job.cleanupViewLogs()).isEqualTo(1);
        assertThat(ids("parking_spot_view_logs")).containsExactlyInAnyOrder(oldView, recentView);
        assertThat(job.cleanupViewLogs()).isEqualTo(1);
        assertThat(ids("parking_spot_view_logs")).containsExactly(recentView);
        assertThat(job.cleanupViewLogs()).isZero();
        assertThat(ids("parking_spot_search_logs")).doesNotContain(oldestSearch);
        assertThat(ids("parking_spot_view_logs")).doesNotContain(oldestView);
    }

    @Test
    void disabledCleanupKeepsEveryRowAndErasureStillDeletesAUsersRowsRegardlessOfAge() {
        UUID erasedUser = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID erasedRecentSearch = insertSearchLog(erasedUser, NOW.minus(Duration.ofDays(1)));
        UUID erasedOldView = insertViewLog(erasedUser, NOW.minus(Duration.ofDays(400)));
        UUID otherOldSearch = insertSearchLog(otherUser, NOW.minus(Duration.ofDays(400)));
        RetentionCleanupJob disabled = new RetentionCleanupJob(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), false, false, false,
                Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(30), 100);

        disabled.cleanup();
        assertThat(ids("parking_spot_search_logs")).containsExactlyInAnyOrder(erasedRecentSearch, otherOldSearch);
        assertThat(ids("parking_spot_view_logs")).containsExactly(erasedOldView);

        // The same statements AccountErasureHandler runs for an erased account (PRIV-001).
        jdbc.update("DELETE FROM parking_spot_search_logs WHERE searcher_user_id = ?", erasedUser);
        jdbc.update("DELETE FROM parking_spot_view_logs WHERE viewer_user_id = ?", erasedUser);
        assertThat(ids("parking_spot_search_logs")).containsExactly(otherOldSearch);
        assertThat(ids("parking_spot_view_logs")).isEmpty();
    }

    private UUID insertSearchLog(UUID userId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO parking_spot_search_logs
                    (id, searcher_user_id, latitude, longitude, radius_meters, result_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, userId, 38.4237, 27.1428, 750.0, 4, Timestamp.from(createdAt));
        return id;
    }

    private UUID insertViewLog(UUID userId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO parking_spot_view_logs (id, spot_id, viewer_user_id, created_at)
                VALUES (?, ?, ?, ?)
                """, id, UUID.randomUUID(), userId, Timestamp.from(createdAt));
        return id;
    }

    private List<UUID> ids(String table) {
        return jdbc.query("SELECT id FROM " + table, (rs, rowNum) -> rs.getObject("id", UUID.class));
    }
}
