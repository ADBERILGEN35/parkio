package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.ConcurrentGenerationException;
import com.parkio.parking.application.LinkCandidateGenerationOrchestrator;
import com.parkio.parking.application.LinkCandidateGenerationService;
import com.parkio.parking.application.port.LinkCandidateGenerationRunPort;
import com.parkio.parking.externalsource.registry.LinkCandidatePolicy;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import com.parkio.parking.infrastructure.persistence.LinkCandidateGenerationRunAdapter;
import com.parkio.parking.infrastructure.persistence.LinkCandidatePairDiscoveryAdapter;
import com.parkio.parking.infrastructure.persistence.RegistryPersistenceAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.parkio.parking.testsupport.PostgisTestImages;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class MunicipalRegistryCandidateGenerationPostgresIT {
    private static final DockerImageName POSTGIS =
            PostgisTestImages.dockerImageName();
    private static final UUID FACILITY_A = UUID.fromString("00000000-0000-0000-0000-000000005001");
    private static final UUID FACILITY_B = UUID.fromString("00000000-0000-0000-0000-000000005002");
    private static final UUID FACILITY_C = UUID.fromString("00000000-0000-0000-0000-000000005003");
    private static final UUID FACILITY_D = UUID.fromString("00000000-0000-0000-0000-000000005004");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS)
            .withDatabaseName("parkio_candidate_generation_it")
            .withUsername("parkio")
            .withPassword("parkio");

    private JdbcClient jdbc;
    private LinkCandidateGenerationRunAdapter runAdapter;
    private LinkCandidateGenerationOrchestrator orchestrator;

    @BeforeEach
    void reset() {
        Flyway flyway = flyway(null);
        flyway.clean();
        flyway.migrate();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        RegistryProperties properties = new RegistryProperties();
        properties.setCandidateGenerationEnabled(true);
        RegistryMetrics metrics = new RegistryMetrics(new SimpleMeterRegistry());
        runAdapter = new LinkCandidateGenerationRunAdapter(jdbc, mapper);
        var generation = new LinkCandidateGenerationService(
                properties, new RegistryPersistenceAdapter(jdbc), mapper, metrics, Clock.systemUTC());
        orchestrator = new LinkCandidateGenerationOrchestrator(
                properties, new LinkCandidatePairDiscoveryAdapter(jdbc), runAdapter,
                generation, mapper, metrics, Clock.systemUTC());
    }

    @Test
    void v32UpgradesToV36AndEnforcesOneRunningPair() {
        Flyway v32 = flyway(MigrationVersion.fromVersion("32"));
        v32.clean();
        v32.migrate();
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(7);
        assertThat(flyway(null).migrate().migrationsExecuted).isZero();
        assertThat(count("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_name='municipal_link_candidate_generation_runs'")).isEqualTo(1);
        assertThat(runAdapter.tryStart(startRequest())).isPresent();
        assertThat(runAdapter.tryStart(startRequest())).isEmpty();
    }

    @Test
    void v33DoesNotSeedCandidatesOrRuns() {
        assertThat(count("SELECT count(*) FROM municipal_link_candidates")).isZero();
        assertThat(count("SELECT count(*) FROM municipal_link_candidate_generation_runs")).isZero();
    }

    @Test
    void boundedDryRunPersistAndIdempotentRerunDoNotMutateRegistryLinks() {
        insertFixtures();
        var dry = orchestrator.generate(request(true, false));
        assertThat(dry.aggregates().pairsConsidered()).isEqualTo(1);
        var persisted = orchestrator.generate(request(false, true));
        var repeated = orchestrator.generate(request(false, true));
        assertThat(persisted.aggregates().candidatesPersisted()).isEqualTo(1);
        assertThat(repeated.aggregates().duplicatesSuppressed()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM municipal_link_candidates")).isEqualTo(1);
    }

    @Test
    void dryRunWritesOnlyGenerationRunAuditAndNoRegistryMutations() {
        insertFixtures();
        Map<String, Long> before = registryMutationCounts();
        long runsBefore = count("SELECT count(*) FROM municipal_link_candidate_generation_runs");

        var result = orchestrator.generate(request(true, false));

        assertThat(registryMutationCounts()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM municipal_link_candidate_generation_runs"))
                .isEqualTo(runsBefore + 1);
        assertThat(result.dryRun()).isTrue();
        assertThat(result.persistCandidates()).isFalse();
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.samplesJson()).isNotBlank().doesNotContain("Ataturk 1");
    }

    @Test
    void persistModeInsertsPendingOnlyAndIsIdempotent() {
        insertFixtures();
        Map<String, Long> before = registryMutationCounts();
        var first = orchestrator.generate(request(false, true));
        var second = orchestrator.generate(request(false, true));

        assertThat(first.aggregates().candidatesPersisted()).isEqualTo(1);
        assertThat(second.aggregates().duplicatesSuppressed()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM municipal_link_candidates WHERE review_state='PENDING'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM municipal_link_review_audit"))
                .isEqualTo(before.get("review"));
        assertNonCandidateMutationCountsUnchanged(before);
    }

    @Test
    void rejectedSameVersionIsSuppressedOnRerun() {
        insertFixtures();
        orchestrator.generate(request(false, true));
        jdbc.sql("""
                UPDATE municipal_link_candidates
                SET review_state='REJECTED', reviewed_by='admin', decision_ts=now(),
                    rejection_reason='not same', version=1, updated_at=now()
                """).update();

        var rerun = orchestrator.generate(request(false, true));

        assertThat(rerun.aggregates().duplicatesSuppressed()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM municipal_link_candidates")).isEqualTo(1);
    }

    @Test
    void changedSourceVersionAllowsNewCandidate() {
        insertFixtures();
        orchestrator.generate(request(false, true));
        jdbc.sql("""
                UPDATE municipal_facility_source_links
                SET raw_record_hash='osm-1-v2', updated_at=now()
                WHERE external_id='osm-1'
                """).update();

        var rerun = orchestrator.generate(request(false, true));

        assertThat(rerun.aggregates().candidatesPersisted()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM municipal_link_candidates")).isEqualTo(2);
    }

    @Test
    void alreadyLinkedIsSkipped() {
        insertFixtures();
        jdbc.sql("""
                INSERT INTO municipal_facility_aliases(
                  from_facility_id,to_facility_id,candidate_id,created_at,created_by)
                VALUES (:from,:to,NULL,now(),'admin')
                """).param("from", FACILITY_B).param("to", FACILITY_A).update();

        var result = orchestrator.generate(request(false, true));

        assertThat(result.aggregates().skips()).containsEntry("already_linked", 1);
        assertThat(count("SELECT count(*) FROM municipal_link_candidates WHERE review_state='PENDING'"))
                .isZero();
    }

    @Test
    void pairLimitAndDeterministicOrderingAreHonored() {
        insertFacility(FACILITY_A, "Konak Otoparki", "OFF_STREET", 38.4200, 27.1400);
        insertLink(FACILITY_A, "izmir-izum-otoparklar", "izum-1");
        insertOsm(FACILITY_D, "osm-3", 38.4203, 27.1403);
        insertOsm(FACILITY_B, "osm-1", 38.4201, 27.1401);
        insertOsm(FACILITY_C, "osm-2", 38.4202, 27.1402);
        var bounded = request(true, false, 1, 2);

        var first = orchestrator.generate(bounded);
        var second = orchestrator.generate(bounded);

        assertThat(first.aggregates().pairsConsidered()).isEqualTo(2);
        assertThat(second.aggregates().pairsConsidered()).isEqualTo(2);
        assertThat(second.samplesJson()).isEqualTo(first.samplesJson());
        assertThat(first.samplesJson()).contains("osm-1", "osm-2").doesNotContain("osm-3");
    }

    @Test
    void lockReleasedAfterFailedRunAllowsRetry() {
        UUID active = runAdapter.tryStart(startRequest()).orElseThrow();
        runAdapter.complete(active, "FAILED",
                new LinkCandidateGenerationRunPort.Aggregates(0, 0, 0, 0, 0, Map.of(), 0, 1),
                "[]", "discovery_failure", Instant.now(), 1);
        assertThat(runAdapter.tryStart(startRequest())).isPresent();
    }

    @Test
    void independentPairLocksDoNotBlockWhenOnlyIzumOsmSupported() {
        assertThatThrownBy(() -> orchestrator.generate(new LinkCandidateGenerationOrchestrator.Request(
                "IZUM", "IZELMAN", null, null, null, null, true, false,
                List.of(), List.of(), null, "admin", "it")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("disabled");
        assertThat(runAdapter.tryStart(startRequest())).isPresent();
        assertThatThrownBy(() -> orchestrator.generate(request(false, false)))
                .isInstanceOf(ConcurrentGenerationException.class);
    }

    @Test
    void concurrentPairConflictsAndDisabledIzelmanPairIsRejected() {
        independentPairLocksDoNotBlockWhenOnlyIzumOsmSupported();
    }

    @Test
    void hardConflictIsClassifiedWithoutApplying() {
        insertFacility(FACILITY_A, "Konak Otoparki", "OFF_STREET", 38.4200, 27.1400);
        insertFacility(FACILITY_B, "Konak Otoparki", "ON_STREET", 38.4201, 27.1401);
        insertLink(FACILITY_A, "izmir-izum-otoparklar", "izum-1");
        insertLink(FACILITY_B, "osm-geofabrik-turkey", "osm-1");
        long aliases = count("SELECT count(*) FROM municipal_facility_aliases");

        var result = orchestrator.generate(request(true, false));

        assertThat(result.aggregates().hardConflicts()).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM municipal_link_candidates WHERE review_state='ACCEPTED'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM municipal_facility_aliases")).isEqualTo(aliases);
    }

    @Test
    void distanceOnlyAndNameOnlyDoNotPersistCandidates() {
        insertFacility(FACILITY_A, "Alpha Garage", "OFF_STREET", 38.4200, 27.1400);
        insertFacility(FACILITY_B, "Completely Different", "OFF_STREET", 38.4201, 27.1401);
        insertFacility(FACILITY_C, "Alpha Garage", "OFF_STREET", 38.4300, 27.1500);
        insertLink(FACILITY_A, "izmir-izum-otoparklar", "izum-1");
        insertLink(FACILITY_B, "osm-geofabrik-turkey", "osm-close");
        insertLink(FACILITY_C, "osm-geofabrik-turkey", "osm-far");
        jdbc.sql("""
                UPDATE municipal_parking_facilities
                SET facility_type='UNKNOWN', capacity_total=NULL,
                    operator_name=CASE id
                      WHEN :a THEN 'Alpha Operator'
                      WHEN :b THEN 'Beta Operator'
                      ELSE 'Alpha Operator'
                    END,
                    address_text=CASE id WHEN :b THEN 'Beta Street' ELSE 'Alpha Street' END
                WHERE id IN (:a,:b,:c)
                """).param("a", FACILITY_A).param("b", FACILITY_B).param("c", FACILITY_C).update();
        jdbc.sql("""
                UPDATE municipal_facility_source_links
                SET source_name=CASE external_id
                      WHEN 'osm-close' THEN 'Completely Different'
                      ELSE 'Alpha Garage'
                    END,
                    source_metadata_json=CASE external_id
                      WHEN 'osm-close' THEN '{"district":"Beta"}'::jsonb
                      ELSE '{"district":"Alpha"}'::jsonb
                    END
                WHERE external_id IN ('izum-1','osm-close','osm-far')
                """).update();

        var result = orchestrator.generate(request(false, true, 1, 10));

        assertThat(result.aggregates().pairsConsidered()).isEqualTo(1);
        assertThat(result.aggregates().skips()).containsEntry("distance_only", 1);
        assertThat(count("SELECT count(*) FROM municipal_link_candidates")).isZero();
    }

    private LinkCandidateGenerationOrchestrator.Request request(boolean dryRun, boolean persist) {
        return request(dryRun, persist, 1, 1);
    }

    private LinkCandidateGenerationOrchestrator.Request request(
            boolean dryRun, boolean persist, int leftLimit, int pairLimit) {
        return new LinkCandidateGenerationOrchestrator.Request(
                "IZUM", "OSM", 100d, leftLimit, pairLimit, 20, dryRun, persist,
                List.of(), List.of(), LinkCandidatePolicy.ALGORITHM_VERSION, "admin", "it");
    }

    private LinkCandidateGenerationRunPort.StartRequest startRequest() {
        return new LinkCandidateGenerationRunPort.StartRequest(
                "IZUM_OSM", LinkCandidatePolicy.ALGORITHM_VERSION, true, false,
                100, 100, 1000, 20, "{}", "admin", "it", Instant.now());
    }

    private void insertFixtures() {
        insertFacility(FACILITY_A, "Konak Otoparki", "OFF_STREET", 38.4200, 27.1400);
        insertFacility(FACILITY_B, "Konak Otoparki", "OFF_STREET", 38.4201, 27.1401);
        insertLink(FACILITY_A, "izmir-izum-otoparklar", "izum-1");
        insertLink(FACILITY_B, "osm-geofabrik-turkey", "osm-1");
    }

    private void insertOsm(UUID id, String externalId, double lat, double lng) {
        insertFacility(id, "Konak Otoparki", "OFF_STREET", lat, lng);
        insertLink(id, "osm-geofabrik-turkey", externalId);
    }

    private void insertFacility(UUID id, String name, String type, double lat, double lng) {
        jdbc.sql("""
                INSERT INTO municipal_parking_facilities(
                  id,operator_name,facility_type,access_classification,display_name,address_text,
                  latitude,longitude,location,capacity_total,active,lifecycle_state,created_at,updated_at)
                VALUES (:id,'Izmir Belediyesi',:type,'PUBLIC',:name,'Ataturk 1',
                  :lat,:lng,ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography,100,true,'ACTIVE',now(),now())
                """).param("id", id).param("type", type).param("name", name)
                .param("lat", lat).param("lng", lng).update();
    }

    private void insertLink(UUID facilityId, String sourceKey, String externalId) {
        jdbc.sql("""
                INSERT INTO municipal_facility_source_links(
                  id,facility_id,source_id,external_id,source_name,source_metadata_json,raw_record_hash,
                  first_seen_at,last_seen_at,last_successful_sync_at,active,created_at,updated_at)
                SELECT :id,:facility,id,:external,:name,'{"district":"Konak"}',:hash,
                  now(),now(),now(),true,now(),now()
                FROM municipal_data_sources WHERE source_key=:sourceKey
                """).param("id", UUID.randomUUID()).param("facility", facilityId)
                .param("external", externalId).param("name", "Konak Otoparki")
                .param("hash", externalId + "-v1").param("sourceKey", sourceKey).update();
    }

    private Map<String, Long> registryMutationCounts() {
        return Map.of(
                "candidates", count("SELECT count(*) FROM municipal_link_candidates"),
                "links", count("SELECT count(*) FROM municipal_facility_source_links"),
                "aliases", count("SELECT count(*) FROM municipal_facility_aliases"),
                "occupancy", count("SELECT count(*) FROM municipal_occupancy_snapshots"),
                "tariffs", count("SELECT count(*) FROM municipal_tariff_assignments"),
                "review", count("SELECT count(*) FROM municipal_link_review_audit"));
    }

    private void assertNonCandidateMutationCountsUnchanged(Map<String, Long> before) {
        Map<String, Long> after = registryMutationCounts();
        assertThat(after.get("links")).isEqualTo(before.get("links"));
        assertThat(after.get("aliases")).isEqualTo(before.get("aliases"));
        assertThat(after.get("occupancy")).isEqualTo(before.get("occupancy"));
        assertThat(after.get("tariffs")).isEqualTo(before.get("tariffs"));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
