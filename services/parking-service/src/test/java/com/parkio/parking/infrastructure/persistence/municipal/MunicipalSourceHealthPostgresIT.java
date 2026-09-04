package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.application.MunicipalSourceSlaPolicy;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import com.parkio.parking.testsupport.PostgisTestImages;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MunicipalSourceHealthPostgresIT {
    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_municipal_health_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired MunicipalSourceSyncRunRepository runs;
    @Autowired MunicipalSourceHealthService healthService;
    @Autowired JdbcTemplate jdbc;

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
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.kafka.ai-validation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.moderation-timeout.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("parkio.municipal.enabled", () -> "true");
        registry.add("parkio.municipal.izum.enabled", () -> "true");
        registry.add("parkio.municipal.izum.scheduler-enabled", () -> "false");
    }

    @Test
    void consecutiveFailuresLatestSuccessIsolationAndRecoveryAreDeterministic() {
        UUID sourceId = jdbc.queryForObject(
                "SELECT id FROM municipal_data_sources WHERE source_key = ?",
                UUID.class,
                IzumMunicipalParkingAdapter.SOURCE_KEY);
        UUID otherSource = jdbc.queryForObject(
                """
                INSERT INTO municipal_data_sources
                    (id, source_key, publisher, dataset_name, canonical_url, access_type,
                     license_identifier, license_text, attribution_text, expected_update_frequency,
                     stale_after_seconds, aging_after_seconds, schema_version, fields_used,
                     active, production_approved, created_at, updated_at)
                VALUES (gen_random_uuid(), 'other-source-health-it', 'Other', 'Other dataset',
                        'https://example.invalid', 'OPEN_API', 'x', 'x', 'x', 'unknown',
                        900, 300, 'v1', '[]', true, false, now(), now())
                RETURNING id
                """,
                UUID.class);

        Instant t0 = Instant.parse("2026-07-30T19:00:00Z");
        insertCompleted(sourceId, "SUCCESS", null, t0);
        insertCompleted(sourceId, "FAILED", "read_timeout", t0.plusSeconds(60));
        insertCompleted(sourceId, "FAILED", "read_timeout", t0.plusSeconds(120));
        insertCompleted(otherSource, "FAILED", "schema_contract", t0.plusSeconds(180));

        // RUNNING must not count
        jdbc.update("""
                INSERT INTO municipal_source_sync_runs
                    (id,source_id,correlation_id,started_at,status,records_received,records_accepted,
                     records_rejected,records_inserted,records_updated,records_unchanged,occupancy_inserted)
                VALUES (?,?,?,?, 'RUNNING',0,0,0,0,0,0,0)
                """, UUID.randomUUID(), sourceId, "running-now", java.sql.Timestamp.from(t0.plusSeconds(200)));

        List<MunicipalSourceSyncRunRepository.CompletedRunView> recent =
                runs.findRecentCompleted(sourceId, 50);
        assertThat(MunicipalSourceSlaPolicy.consecutiveFailures(recent.stream()
                        .map(row -> new MunicipalSourceSlaPolicy.CompletedRun(
                                row.status(), row.errorCategory(), row.startedAt(), row.completedAt()))
                        .toList()))
                .isEqualTo(2);
        assertThat(runs.findLatestSuccessAt(sourceId)).contains(t0);
        assertThat(runs.countFailuresSince(sourceId, t0)).isEqualTo(2);
        assertThat(runs.countStaleRunning(sourceId, t0.plusSeconds(250))).isEqualTo(1);

        MunicipalSourceHealthService.Snapshot before = healthService.izumSnapshot();
        assertThat(before.consecutiveFailures()).isEqualTo(2);
        assertThat(before.evaluation().lastFailureCategory()).isEqualTo("read_timeout");
        assertThat(before.evaluation().staleRunningOperations()).isGreaterThan(0);

        // Historical failure category remains immutable when a later success is recorded.
        String historicalCategory = jdbc.queryForObject("""
                SELECT error_category FROM municipal_source_sync_runs
                WHERE source_id = ? AND status = 'FAILED'
                ORDER BY started_at ASC LIMIT 1
                """, String.class, sourceId);
        assertThat(historicalCategory).isEqualTo("read_timeout");

        // Clear the intentional stale RUNNING lock before recovery assertions.
        jdbc.update("DELETE FROM municipal_source_sync_runs WHERE status = 'RUNNING' AND source_id = ?", sourceId);

        Instant recoveredAt = Instant.now().minusSeconds(30);
        insertCompleted(sourceId, "SUCCESS", null, recoveredAt);
        jdbc.update("UPDATE municipal_data_sources SET last_successful_sync_at = ? WHERE id = ?",
                java.sql.Timestamp.from(recoveredAt), sourceId);
        MunicipalSourceHealthService.Snapshot after = healthService.izumSnapshot();
        assertThat(after.consecutiveFailures()).isZero();
        assertThat(after.evaluation().staleRunningOperations()).isZero();
        assertThat(after.operationalState()).isIn(
                MunicipalSourceOperationalState.RECOVERING, MunicipalSourceOperationalState.HEALTHY);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM municipal_occupancy_snapshots s
                JOIN municipal_data_sources d ON d.id = s.source_id
                WHERE d.source_key IN ('osm-geofabrik-turkey', 'izelman-open-parking-facilities')
                """, Long.class)).isZero();
    }

    @Test
    void timeoutAndSchemaCategoriesPersistDistinctWireValues() {
        jdbc.update("DELETE FROM municipal_source_sync_runs WHERE status = 'RUNNING'");
        UUID sourceId = jdbc.queryForObject(
                "SELECT id FROM municipal_data_sources WHERE source_key = ?",
                UUID.class,
                IzumMunicipalParkingAdapter.SOURCE_KEY);
        Instant tTimeout = Instant.now().minusSeconds(20);
        Instant tSchema = Instant.now().minusSeconds(10);
        UUID timeoutRun = runs.tryStart(sourceId, "timeout-run", tTimeout).orElseThrow();
        runs.complete(
                timeoutRun,
                tTimeout.plusSeconds(1),
                new MunicipalSyncResult(
                        MunicipalSyncRunStatus.FAILED, 0, 0, 0, 0, 0, 0, 0, "read_timeout", "bounded"),
                null,
                null);
        UUID schemaRun = runs.tryStart(sourceId, "schema-run", tSchema).orElseThrow();
        runs.complete(
                schemaRun,
                tSchema.plusSeconds(1),
                new MunicipalSyncResult(
                        MunicipalSyncRunStatus.FAILED, 0, 0, 0, 0, 0, 0, 0, "schema_contract", "bounded"),
                null,
                null);

        assertThat(runs.findLatestCompleted(sourceId))
                .get()
                .extracting(MunicipalSourceSyncRunRepository.LatestRun::errorCategory)
                .isEqualTo("schema_contract");
        assertThat(jdbc.queryForObject("""
                SELECT error_category FROM municipal_source_sync_runs WHERE correlation_id = 'timeout-run'
                """, String.class)).isEqualTo("read_timeout");
    }

    private void insertCompleted(UUID sourceId, String status, String category, Instant at) {
        jdbc.update("""
                INSERT INTO municipal_source_sync_runs
                    (id,source_id,correlation_id,started_at,completed_at,status,records_received,records_accepted,
                     records_rejected,records_inserted,records_updated,records_unchanged,occupancy_inserted,
                     error_category)
                VALUES (?,?,?,?,?,?,0,0,0,0,0,0,0,?)
                """,
                UUID.randomUUID(),
                sourceId,
                "corr-" + UUID.randomUUID(),
                java.sql.Timestamp.from(at),
                java.sql.Timestamp.from(at),
                status,
                category);
    }
}
