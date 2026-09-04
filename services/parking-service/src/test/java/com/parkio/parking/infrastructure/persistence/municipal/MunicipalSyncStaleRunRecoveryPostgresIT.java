package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.MunicipalSyncRunRecoveryService;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.externalsource.MunicipalSourceFailureCategory;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.ispark.IsparkMunicipalParkingAdapter;
import com.parkio.parking.testsupport.PostgisTestImages;
import java.time.Instant;
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

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MunicipalSyncStaleRunRecoveryPostgresIT {
    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_municipal_stale_recovery_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired MunicipalSourceSyncRunRepository runs;
    @Autowired MunicipalSyncRunRecoveryService recovery;
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
        registry.add("parkio.municipal.ispark.enabled", () -> "true");
        registry.add("parkio.municipal.ispark.scheduler-enabled", () -> "false");
        registry.add("parkio.municipal.sync.stale-run-recovery-enabled", () -> "true");
        registry.add("parkio.municipal.sync.stale-running-threshold", () -> "20m");
        registry.add("parkio.municipal.sync.stale-run-watchdog-enabled", () -> "false");
    }

    @BeforeEach
    void clearRuns() {
        jdbc.update("DELETE FROM municipal_source_sync_runs");
    }

    @Test
    void staleRunningIsRecoveredAndTryStartSucceedsWithoutMutatingFacilities() {
        UUID izum = sourceId(IzumMunicipalParkingAdapter.SOURCE_KEY);
        long facilitiesBefore = countFacilities();
        long linksBefore = countLinks();
        long occBefore = countOccupancy();

        Instant staleStart = Instant.now().minusSeconds(8 * 3600);
        UUID staleRun = insertRunning(izum, staleStart, "stale-orphan");

        assertThat(recovery.recoverStaleRunning()).isEqualTo(1);
        assertThat(status(staleRun)).isEqualTo("FAILED");
        assertThat(category(staleRun)).isEqualTo(
                MunicipalSourceFailureCategory.STALE_RUN_RECOVERED.wireValue());
        assertThat(jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM municipal_source_sync_runs WHERE id = ?",
                Boolean.class, staleRun)).isTrue();

        assertThat(runs.tryStart(izum, "after-recovery", Instant.now())).isPresent();
        assertThat(recovery.recoverStaleRunning()).isZero();

        assertThat(countFacilities()).isEqualTo(facilitiesBefore);
        assertThat(countLinks()).isEqualTo(linksBefore);
        assertThat(countOccupancy()).isEqualTo(occBefore);
    }

    @Test
    void freshRunningIsProtectedAndStillBlocksConcurrentTryStart() {
        UUID izum = sourceId(IzumMunicipalParkingAdapter.SOURCE_KEY);
        UUID fresh = insertRunning(izum, Instant.now().minusSeconds(60), "fresh-running");

        assertThat(recovery.recoverStaleRunning()).isZero();
        assertThat(status(fresh)).isEqualTo("RUNNING");
        assertThat(runs.tryStart(izum, "blocked", Instant.now())).isEmpty();
    }

    @Test
    void completeRequiresRunningOwnership() {
        UUID izum = sourceId(IzumMunicipalParkingAdapter.SOURCE_KEY);
        UUID run = insertRunning(izum, Instant.now().minusSeconds(30), "owned");
        assertThat(runs.isRunning(run)).isTrue();

        MunicipalSyncResult success = new MunicipalSyncResult(
                MunicipalSyncRunStatus.SUCCESS, 1, 1, 0, 0, 0, 1, 0, null, null);
        assertThat(runs.complete(run, Instant.now(), success, null, null)).isTrue();
        assertThat(status(run)).isEqualTo("SUCCESS");
        assertThat(runs.isRunning(run)).isFalse();

        MunicipalSyncResult late = new MunicipalSyncResult(
                MunicipalSyncRunStatus.FAILED, 0, 0, 0, 0, 0, 0, 0, "read_timeout", "late");
        assertThat(runs.complete(run, Instant.now(), late, null, null)).isFalse();
        assertThat(status(run)).isEqualTo("SUCCESS");
    }

    @Test
    void lateCompleteCannotOverwriteStaleRecovery() {
        UUID izum = sourceId(IzumMunicipalParkingAdapter.SOURCE_KEY);
        UUID run = insertRunning(izum, Instant.now().minusSeconds(8 * 3600), "late-race");
        assertThat(recovery.recoverStaleRunning()).isEqualTo(1);
        assertThat(category(run)).isEqualTo(
                MunicipalSourceFailureCategory.STALE_RUN_RECOVERED.wireValue());

        MunicipalSyncResult success = new MunicipalSyncResult(
                MunicipalSyncRunStatus.SUCCESS, 5, 5, 0, 0, 0, 5, 5, null, null);
        assertThat(runs.complete(run, Instant.now(), success, null, null)).isFalse();
        assertThat(status(run)).isEqualTo("FAILED");
        assertThat(category(run)).isEqualTo(
                MunicipalSourceFailureCategory.STALE_RUN_RECOVERED.wireValue());
        assertThat(jdbc.queryForObject(
                "SELECT records_accepted FROM municipal_source_sync_runs WHERE id = ?",
                Integer.class, run)).isZero();
    }

    @Test
    void recoveryWinsRaceAgainstCompleteWhenAlreadyTerminalized() {
        UUID izum = sourceId(IzumMunicipalParkingAdapter.SOURCE_KEY);
        UUID run = insertRunning(izum, Instant.now().minusSeconds(8 * 3600), "race");
        // Worker completes first.
        MunicipalSyncResult success = new MunicipalSyncResult(
                MunicipalSyncRunStatus.SUCCESS, 1, 1, 0, 0, 0, 1, 0, null, null);
        assertThat(runs.complete(run, Instant.now(), success, null, null)).isTrue();
        // Recovery finds nothing (no longer RUNNING).
        assertThat(recovery.recoverStaleRunning()).isZero();
        assertThat(status(run)).isEqualTo("SUCCESS");
    }

    @Test
    void multiSourceOnlyStaleRecovered() {
        UUID izum = sourceId(IzumMunicipalParkingAdapter.SOURCE_KEY);
        UUID ispark = sourceId(IsparkMunicipalParkingAdapter.SOURCE_KEY);
        UUID staleIzum = insertRunning(izum, Instant.now().minusSeconds(8 * 3600), "stale-izum");
        UUID freshIspark = insertRunning(ispark, Instant.now().minusSeconds(30), "fresh-ispark");

        assertThat(recovery.recoverStaleRunning()).isEqualTo(1);
        assertThat(status(staleIzum)).isEqualTo("FAILED");
        assertThat(status(freshIspark)).isEqualTo("RUNNING");
        assertThat(runs.tryStart(izum, "izum-next", Instant.now())).isPresent();
        assertThat(runs.tryStart(ispark, "ispark-blocked", Instant.now())).isEmpty();
    }

    @Test
    void thresholdBoundaryUsesStrictOlderThan() {
        UUID izum = sourceId(IzumMunicipalParkingAdapter.SOURCE_KEY);
        Instant now = Instant.now();
        // Exactly 20m ago is NOT strictly older than cutoff (now-20m); leave RUNNING.
        UUID atBoundary = insertRunning(izum, now.minusSeconds(20 * 60), "boundary");
        Instant olderThan = now.minusSeconds(20 * 60);
        assertThat(runs.recoverStaleRunning(olderThan, now)).isEmpty();
        assertThat(status(atBoundary)).isEqualTo("RUNNING");

        // One second older than cutoff → recovered.
        UUID older = insertRunning(
                sourceId(IsparkMunicipalParkingAdapter.SOURCE_KEY),
                now.minusSeconds(20 * 60 + 1),
                "older");
        assertThat(runs.recoverStaleRunning(olderThan, now)).hasSize(1);
        assertThat(status(older)).isEqualTo("FAILED");
    }

    @Test
    void tryStartSelfHealsStaleLock() {
        UUID izum = sourceId(IzumMunicipalParkingAdapter.SOURCE_KEY);
        UUID stale = insertRunning(izum, Instant.now().minusSeconds(8 * 3600), "self-heal");
        assertThat(runs.tryStart(izum, "healed-start", Instant.now())).isPresent();
        assertThat(status(stale)).isEqualTo("FAILED");
        assertThat(category(stale)).isEqualTo(
                MunicipalSourceFailureCategory.STALE_RUN_RECOVERED.wireValue());
    }

    private UUID sourceId(String key) {
        return jdbc.queryForObject(
                "SELECT id FROM municipal_data_sources WHERE source_key = ?", UUID.class, key);
    }

    private UUID insertRunning(UUID sourceId, Instant started, String correlation) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO municipal_source_sync_runs
                    (id,source_id,correlation_id,started_at,status,records_received,records_accepted,
                     records_rejected,records_inserted,records_updated,records_unchanged,occupancy_inserted)
                VALUES (?,?,?,?, 'RUNNING',0,0,0,0,0,0,0)
                """, id, sourceId, correlation, java.sql.Timestamp.from(started));
        return id;
    }

    private String status(UUID runId) {
        return jdbc.queryForObject(
                "SELECT status FROM municipal_source_sync_runs WHERE id = ?", String.class, runId);
    }

    private String category(UUID runId) {
        return jdbc.queryForObject(
                "SELECT error_category FROM municipal_source_sync_runs WHERE id = ?", String.class, runId);
    }

    private long countFacilities() {
        return jdbc.queryForObject("SELECT count(*) FROM municipal_parking_facilities", Long.class);
    }

    private long countLinks() {
        return jdbc.queryForObject("SELECT count(*) FROM municipal_facility_source_links", Long.class);
    }

    private long countOccupancy() {
        return jdbc.queryForObject("SELECT count(*) FROM municipal_occupancy_snapshots", Long.class);
    }
}
