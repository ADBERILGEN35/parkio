package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.registry.CanonicalFieldPrecedencePolicy;
import com.parkio.parking.externalsource.registry.FieldProvenanceSelection;
import com.parkio.parking.externalsource.registry.LinkCandidateEvidence;
import com.parkio.parking.externalsource.registry.LinkCandidatePolicy;
import com.parkio.parking.externalsource.registry.SourceLifecyclePolicy;
import com.parkio.parking.externalsource.registry.TariffAssignmentPolicy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.parkio.parking.testsupport.PostgisTestImages;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class MunicipalRegistryRuntimePostgresIT {
    private static final DockerImageName POSTGIS =
            PostgisTestImages.dockerImageName();
    private static final UUID FACILITY_A = UUID.fromString("00000000-0000-0000-0000-000000004001");
    private static final UUID FACILITY_B = UUID.fromString("00000000-0000-0000-0000-000000004002");
    private static final UUID CANDIDATE = UUID.fromString("00000000-0000-0000-0000-000000004003");
    private static final UUID IZUM_SOURCE = UUID.fromString("a1111111-1111-4111-8111-111111111101");
    private static final UUID OSM_SOURCE = UUID.fromString("22222222-2222-2222-2222-222222222201");
    private static final UUID IZELMAN_SOURCE = UUID.fromString("33333333-3333-4333-8333-333333333301");
    private static final UUID IZUM_LINK = UUID.fromString("00000000-0000-0000-0000-000000004011");
    private static final UUID OSM_LINK = UUID.fromString("00000000-0000-0000-0000-000000004012");
    private static final UUID SYNC_RUN = UUID.fromString("00000000-0000-0000-0000-000000004021");
    private static final UUID SNAPSHOT = UUID.fromString("00000000-0000-0000-0000-000000004022");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS)
            .withDatabaseName("parkio_registry_runtime_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @BeforeEach
    void reset() {
        Flyway flyway = flyway(null);
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void v32UpgradesToV36ExactlyOnceWithoutDamagingExistingRegistryData() throws Exception {
        Flyway v32 = flyway(MigrationVersion.fromVersion("32"));
        v32.clean();
        v32.migrate();
        try (Connection connection = open()) {
            insertFacility(connection, FACILITY_A, "Existing facility");
            assertThat(version(connection)).isEqualTo("32");
        }

        Flyway latest = flyway(null);
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(7);
        assertThat(latest.migrate().migrationsExecuted).isZero();

        try (Connection connection = open()) {
            assertThat(version(connection)).isEqualTo("39");
            assertThat(count(connection, "SELECT count(*) FROM municipal_parking_facilities WHERE id='" + FACILITY_A + "'"))
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM municipal_link_candidates")).isZero();
            for (String sourceKey : new String[] {
                    "izmir-izum-otoparklar", "osm-geofabrik-turkey", "izelman-open-parking-facilities",
                    "istanbul-ispark-parks", "ankara-anpark-parks", "konya-bb-otopark-bilgileri",
                    "kayseri-bb-otoparklar"}) {
                assertThat(count(connection, "SELECT count(*) FROM municipal_data_sources WHERE source_key='"
                        + sourceKey + "'")).as(sourceKey).isEqualTo(1);
            }
            for (String table : new String[] {
                    "municipal_data_sources", "municipal_facility_source_links",
                    "municipal_occupancy_snapshots", "municipal_tariff_plans",
                    "municipal_tariff_rate_bands", "municipal_tariff_assignments",
                    "municipal_link_candidates", "municipal_link_review_audit",
                    "municipal_facility_aliases", "municipal_facility_field_provenance",
                    "municipal_link_candidate_generation_runs"}) {
                assertThat(tableExists(connection, table)).as(table).isTrue();
            }
            assertThat(constraintExists(connection, "ck_municipal_alias_not_self")).isTrue();
            assertThat(constraintExists(connection, "uq_municipal_candidate_source_versions")).isTrue();
            assertThat(indexExists(connection, "idx_municipal_candidate_queue")).isTrue();
            assertThat(indexExists(connection, "idx_municipal_facility_lifecycle")).isTrue();
            assertThat(indexExists(connection, "uq_mlcg_runs_one_running")).isTrue();
        }
    }

    @Test
    void candidateIdentityConstraintsAuditImmutabilityAndOptimisticVersionAreEnforced() throws Exception {
        try (Connection connection = open()) {
            insertFacility(connection, FACILITY_A, "A");
            insertFacility(connection, FACILITY_B, "B");
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO municipal_facility_aliases(from_facility_id,to_facility_id,created_at,created_by)
                    VALUES ('%s','%s',now(),'test')
                    """.formatted(FACILITY_A, FACILITY_A))).isInstanceOf(SQLException.class);

            insertCandidate(connection, CANDIDATE, "v1", "v1");
            assertThatThrownBy(() -> insertCandidate(connection, UUID.randomUUID(), "v1", "v1"))
                    .isInstanceOf(SQLException.class);
            insertCandidate(connection, UUID.randomUUID(), "v2", "v1");
            assertThat(count(connection, "SELECT count(*) FROM municipal_link_candidates")).isEqualTo(2);

            assertThat(update(connection, "UPDATE municipal_link_candidates SET review_state='REJECTED',version=version+1 "
                    + "WHERE id='" + CANDIDATE + "' AND version=0")).isEqualTo(1);
            assertThat(update(connection, "UPDATE municipal_link_candidates SET review_state='DISTINCT',version=version+1 "
                    + "WHERE id='" + CANDIDATE + "' AND version=0")).isZero();

            execute(connection, """
                    INSERT INTO municipal_link_review_audit(
                      id,candidate_id,previous_state,new_state,reviewer,decision_reason,candidate_version,decision_ts)
                    VALUES ('00000000-0000-0000-0000-000000004099','%s','PENDING','REJECTED','admin','test',1,now())
                    """.formatted(CANDIDATE));
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE municipal_link_review_audit SET reviewer='mutated' WHERE candidate_id='" + CANDIDATE + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");
        }
    }

    @Test
    void acceptAttachesLinksPreservesOccupancyAndIsIdempotent() throws Exception {
        try (Connection connection = open()) {
            insertAcceptedFixture(connection);

            assertThat(count(connection, "SELECT count(*) FROM municipal_facility_source_links "
                    + "WHERE facility_id='" + FACILITY_A + "' AND external_id IN ('izum-1','osm-1')")).isEqualTo(2);
            assertThat(count(connection, "SELECT count(DISTINCT external_id) FROM municipal_facility_source_links "
                    + "WHERE external_id IN ('izum-1','osm-1')")).isEqualTo(2);
            assertThat(count(connection, "SELECT count(*) FROM municipal_occupancy_snapshots "
                    + "WHERE facility_id='" + FACILITY_A + "' AND available_spaces=12")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM municipal_occupancy_snapshots")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM municipal_parking_facilities WHERE id='" + FACILITY_B
                    + "' AND lifecycle_state='SUPERSEDED' AND superseded_by_id='" + FACILITY_A + "' AND active=false"))
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM municipal_facility_aliases WHERE from_facility_id='"
                    + FACILITY_B + "' AND to_facility_id='" + FACILITY_A + "' AND candidate_id='" + CANDIDATE + "'"))
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM municipal_link_review_audit WHERE candidate_id='"
                    + CANDIDATE + "' AND new_state='ACCEPTED'"))
                    .isEqualTo(1);

            assertThat(update(connection, "UPDATE municipal_link_candidates SET version=version+1 WHERE id='"
                    + CANDIDATE + "' AND review_state='PENDING' AND version=0")).isZero();
            update(connection, "UPDATE municipal_facility_source_links SET facility_id='" + FACILITY_A
                    + "' WHERE id IN ('" + IZUM_LINK + "','" + OSM_LINK + "')");
            assertThat(count(connection, "SELECT count(*) FROM municipal_facility_source_links "
                    + "WHERE external_id IN ('izum-1','osm-1')")).isEqualTo(2);
        }
    }

    @Test
    void rejectSuppressesSameSourceVersionAndAllowsVersionChange() throws Exception {
        try (Connection connection = open()) {
            insertCandidate(connection, CANDIDATE, "v1", "v1");
            assertThat(update(connection, "UPDATE municipal_link_candidates SET review_state='REJECTED',"
                    + "rejection_reason='distinct facilities',version=version+1,updated_at=now() WHERE id='"
                    + CANDIDATE + "' AND version=0")).isEqualTo(1);

            assertThat(insertCandidateIgnoringConflict(connection, UUID.randomUUID(), "v1", "v1")).isZero();
            assertThat(insertCandidateIgnoringConflict(connection, UUID.randomUUID(), "v2", "v1")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM municipal_link_candidates")).isEqualTo(2);
            assertThat(count(connection, "SELECT count(*) FROM municipal_link_candidates WHERE review_state='REJECTED'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void hardConflictCandidateRemainsReviewVisibleAndCannotQualifyForAcceptance() throws Exception {
        var score = LinkCandidatePolicy.evaluate(new LinkCandidateEvidence(
                "izmir-izum-otoparklar", "izum-1", "v1",
                "osm-geofabrik-turkey", "osm-1", "v1",
                5, 1, 1,
                MunicipalFacilityType.ON_STREET, MunicipalFacilityType.OFF_STREET,
                MunicipalAccessClassification.PUBLIC, MunicipalAccessClassification.PUBLIC,
                100, 100, true, true));
        assertThat(score.hardConflicts()).contains("facility_type_exclusive");
        assertThat(score.reviewRequired()).isTrue();
        assertThat(score.candidate()).isFalse();

        try (Connection connection = open()) {
            insertCandidate(connection, CANDIDATE, "v1", "v1", "[\"facility_type_exclusive\"]");
            assertThat(count(connection, "SELECT count(*) FROM municipal_link_candidates WHERE id='" + CANDIDATE
                    + "' AND review_state='PENDING' AND jsonb_array_length(hard_conflicts)=1")).isEqualTo(1);
        }
    }

    @Test
    void reopenReversesSupersedeWithoutDeletingHistory() throws Exception {
        try (Connection connection = open()) {
            insertAcceptedFixture(connection);

            update(connection, "UPDATE municipal_facility_source_links SET facility_id='" + FACILITY_B
                    + "' WHERE id='" + OSM_LINK + "'");
            update(connection, "UPDATE municipal_parking_facilities SET lifecycle_state='ACTIVE',"
                    + "superseded_by_id=NULL,active=true WHERE id='" + FACILITY_B + "'");
            update(connection, "DELETE FROM municipal_facility_aliases WHERE candidate_id='" + CANDIDATE + "'");
            assertThat(update(connection, "UPDATE municipal_link_candidates SET review_state='REOPENED',"
                    + "chosen_facility_id=NULL,version=version+1,updated_at=now() WHERE id='" + CANDIDATE
                    + "' AND version=1")).isEqualTo(1);
            insertAudit(connection, UUID.randomUUID(), "ACCEPTED", "REOPENED", 2);

            assertThat(count(connection, "SELECT count(*) FROM municipal_parking_facilities WHERE id='" + FACILITY_B
                    + "' AND lifecycle_state='ACTIVE' AND superseded_by_id IS NULL AND active=true")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM municipal_facility_aliases WHERE candidate_id='"
                    + CANDIDATE + "'")).isZero();
            assertThat(count(connection, "SELECT count(*) FROM municipal_facility_source_links WHERE id='" + OSM_LINK
                    + "' AND facility_id='" + FACILITY_B + "'")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM municipal_occupancy_snapshots WHERE facility_id='"
                    + FACILITY_A + "' AND available_spaces=12")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM municipal_link_review_audit WHERE candidate_id='"
                    + CANDIDATE + "'")).isEqualTo(2);
            assertThat(count(connection, "SELECT count(*) FROM municipal_facility_source_links WHERE id IN ('"
                    + IZUM_LINK + "','" + OSM_LINK + "')")).isEqualTo(2);
        }
    }

    @Test
    void sourceLifecycleDeactivatesOnlyOwnLink() throws Exception {
        try (Connection connection = open()) {
            insertFacility(connection, FACILITY_A, "A");
            insertSourceLink(connection, IZUM_LINK, FACILITY_A, IZUM_SOURCE, "izum-1");
            insertSourceLink(connection, OSM_LINK, FACILITY_A, OSM_SOURCE, "osm-1");

            assertThat(update(connection, "UPDATE municipal_facility_source_links SET active=false,updated_at=now() "
                    + "WHERE id='" + OSM_LINK + "'")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM municipal_facility_source_links WHERE id='" + OSM_LINK
                    + "' AND active=false")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM municipal_facility_source_links WHERE id='" + IZUM_LINK
                    + "' AND active=true")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM municipal_parking_facilities WHERE id='" + FACILITY_A
                    + "' AND active=true")).isEqualTo(1);
        }
    }

    @Test
    void unpublishedIzelmanDoesNotCreateOccupancy() throws Exception {
        assertThat(CanonicalFieldPrecedencePolicy.mayCreateOccupancySnapshot("osm-geofabrik-turkey")).isFalse();
        assertThat(CanonicalFieldPrecedencePolicy.mayCreateOccupancySnapshot("izelman-open-parking-facilities")).isFalse();

        try (Connection connection = open()) {
            insertFacility(connection, FACILITY_A, "IZELMAN-only facility");
            insertSourceLink(connection, IZUM_LINK, FACILITY_A, IZELMAN_SOURCE, "izelman-1");
            assertThat(count(connection, "SELECT count(*) FROM municipal_occupancy_snapshots WHERE facility_id='"
                    + FACILITY_A + "'")).isZero();
        }
    }
    @Test
    void conservativePoliciesRejectSingleSignalOccupancyLeakWeakTariffsAndPartialDeactivation() {
        assertThat(LinkCandidatePolicy.evaluate(evidence(10, 0, 0)).candidate()).isFalse();
        assertThat(LinkCandidatePolicy.evaluate(evidence(500, 1, 0)).candidate()).isFalse();
        assertThat(CanonicalFieldPrecedencePolicy.mayCreateOccupancySnapshot("izmir-izum-otoparklar")).isTrue();
        assertThat(CanonicalFieldPrecedencePolicy.mayCreateOccupancySnapshot("osm-geofabrik-turkey")).isFalse();
        assertThat(CanonicalFieldPrecedencePolicy.mayCreateOccupancySnapshot("izelman-open-parking-facilities")).isFalse();

        assertThat(TariffAssignmentPolicy.mayAssign(new TariffAssignmentPolicy.Evidence(
                true, Set.of("official_tariff_code"), FieldProvenanceSelection.SourceAgeClass.CURRENT))).isTrue();
        assertThat(TariffAssignmentPolicy.mayAssign(new TariffAssignmentPolicy.Evidence(
                true, Set.of("proximity", "similar_name"), FieldProvenanceSelection.SourceAgeClass.CURRENT))).isFalse();
        assertThat(TariffAssignmentPolicy.mayAssign(new TariffAssignmentPolicy.Evidence(
                true, Set.of("official_tariff_code"), FieldProvenanceSelection.SourceAgeClass.AGING))).isFalse();
        assertThat(TariffAssignmentPolicy.mayAssign(new TariffAssignmentPolicy.Evidence(
                true, Set.of("official_tariff_code"), FieldProvenanceSelection.SourceAgeClass.HISTORICAL))).isFalse();

        assertThat(SourceLifecyclePolicy.decide(SourceLifecyclePolicy.SourceEvent.FAILURE, false, false, false)
                .deactivateSourceLink()).isFalse();
        assertThat(SourceLifecyclePolicy.decide(SourceLifecyclePolicy.SourceEvent.PARTIAL_SUCCESS, false, false, false)
                .deactivateSourceLink()).isFalse();
    }

    private static void insertAcceptedFixture(Connection connection) throws SQLException {
        insertFacility(connection, FACILITY_A, "IZUM facility");
        insertFacility(connection, FACILITY_B, "OSM facility");
        insertSourceLink(connection, IZUM_LINK, FACILITY_A, IZUM_SOURCE, "izum-1");
        insertSourceLink(connection, OSM_LINK, FACILITY_B, OSM_SOURCE, "osm-1");
        execute(connection, """
                INSERT INTO municipal_source_sync_runs(
                  id,source_id,correlation_id,started_at,completed_at,status,occupancy_inserted)
                VALUES ('%s','%s','runtime-it',now(),now(),'SUCCESS',1)
                """.formatted(SYNC_RUN, IZUM_SOURCE));
        execute(connection, """
                INSERT INTO municipal_occupancy_snapshots(
                  id,facility_id,source_id,source_link_id,sync_run_id,source_observed_at,fetched_at,
                  timestamp_provenance,capacity_total,occupied_spaces,available_spaces,occupancy_status,
                  raw_record_hash,created_at)
                VALUES ('%s','%s','%s','%s','%s',now(),now(),'SOURCE',40,28,12,'LIVE','snapshot-hash',now())
                """.formatted(SNAPSHOT, FACILITY_A, IZUM_SOURCE, IZUM_LINK, SYNC_RUN));
        insertCandidate(connection, CANDIDATE, "v1", "v1");
        update(connection, "UPDATE municipal_link_candidates SET facility_a_id='" + FACILITY_A
                + "',facility_b_id='" + FACILITY_B + "' WHERE id='" + CANDIDATE + "'");

        update(connection, "UPDATE municipal_facility_source_links SET facility_id='" + FACILITY_A
                + "',updated_at=now() WHERE id IN ('" + IZUM_LINK + "','" + OSM_LINK + "')");
        update(connection, "UPDATE municipal_parking_facilities SET lifecycle_state='SUPERSEDED',"
                + "superseded_by_id='" + FACILITY_A + "',active=false,updated_at=now() WHERE id='" + FACILITY_B + "'");
        execute(connection, "INSERT INTO municipal_facility_aliases(from_facility_id,to_facility_id,candidate_id,"
                + "created_at,created_by) VALUES ('" + FACILITY_B + "','" + FACILITY_A + "','" + CANDIDATE
                + "',now(),'admin')");
        if (update(connection, "UPDATE municipal_link_candidates SET review_state='ACCEPTED',reviewed_by='admin',"
                + "decision_ts=now(),chosen_facility_id='" + FACILITY_A + "',version=version+1,updated_at=now() "
                + "WHERE id='" + CANDIDATE + "' AND version=0") != 1) {
            throw new SQLException("candidate accept update failed");
        }
        insertAudit(connection, UUID.randomUUID(), "PENDING", "ACCEPTED", 1);
    }

    private static void insertSourceLink(
            Connection connection, UUID id, UUID facilityId, UUID sourceId, String externalId) throws SQLException {
        execute(connection, """
                INSERT INTO municipal_facility_source_links(
                  id,facility_id,source_id,external_id,source_name,source_metadata_json,raw_record_hash,
                  first_seen_at,last_seen_at,last_successful_sync_at,active,created_at,updated_at)
                VALUES ('%s','%s','%s','%s','%s','{}','%s-hash',now(),now(),now(),true,now(),now())
                """.formatted(id, facilityId, sourceId, externalId, externalId, externalId));
    }

    private static void insertAudit(
            Connection connection, UUID id, String previousState, String newState, long version) throws SQLException {
        execute(connection, """
                INSERT INTO municipal_link_review_audit(
                  id,candidate_id,previous_state,new_state,reviewer,decision_reason,chosen_facility_id,
                  candidate_version,decision_ts)
                VALUES ('%s','%s','%s','%s','admin','runtime integration review','%s',%d,now())
                """.formatted(id, CANDIDATE, previousState, newState, FACILITY_A, version));
    }
    private static LinkCandidateEvidence evidence(double distance, double name, double operator) {
        return new LinkCandidateEvidence(
                "izmir-izum-otoparklar", "izum-1", "v1",
                "osm-geofabrik-turkey", "osm-1", "v1",
                distance, name, operator,
                MunicipalFacilityType.UNKNOWN, MunicipalFacilityType.UNKNOWN,
                MunicipalAccessClassification.PUBLIC, MunicipalAccessClassification.PUBLIC,
                null, null, false, false);
    }

    private static void insertFacility(Connection connection, UUID id, String name) throws SQLException {
        execute(connection, """
                INSERT INTO municipal_parking_facilities(
                  id,facility_type,display_name,latitude,longitude,location,created_at,updated_at)
                VALUES ('%s','OFF_STREET','%s',38.42,27.14,
                  ST_SetSRID(ST_MakePoint(27.14,38.42),4326)::geography,now(),now())
                """.formatted(id, name));
    }

    private static void insertCandidate(Connection connection, UUID id, String versionA, String versionB)
            throws SQLException {
        insertCandidate(connection, id, versionA, versionB, "[]");
    }

    private static void insertCandidate(
            Connection connection, UUID id, String versionA, String versionB, String hardConflicts)
            throws SQLException {
        execute(connection, candidateInsertSql(id, versionA, versionB, hardConflicts, false));
    }

    private static int insertCandidateIgnoringConflict(
            Connection connection, UUID id, String versionA, String versionB) throws SQLException {
        return update(connection, candidateInsertSql(id, versionA, versionB, "[]", true));
    }

    private static String candidateInsertSql(
            UUID id, String versionA, String versionB, String hardConflicts, boolean ignoreConflict) {
        return """
                INSERT INTO municipal_link_candidates(
                  id,source_key_a,external_id_a,source_key_b,external_id_b,source_family_pair,
                  evidence_signals_json,score_components_json,total_score,hard_conflicts,generated_at,
                  source_version_a,source_version_b,algorithm_version,created_at,updated_at)
                VALUES ('%s','izmir-izum-otoparklar','izum-1','osm-geofabrik-turkey','osm-1','IZUM_OSM',
                  '{}','{}',0.7,'%s',now(),'%s','%s','registry-link-candidate-v1',now(),now())
                %s
                """.formatted(id, hardConflicts, versionA, versionB,
                        ignoreConflict ? "ON CONFLICT DO NOTHING" : "");
    }

    private static Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String version(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")) {
            result.next();
            return result.getString(1);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static int update(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        return count(connection, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                + "AND table_name='" + table + "'") == 1;
    }

    private static boolean constraintExists(Connection connection, String constraint) throws SQLException {
        return count(connection, "SELECT count(*) FROM pg_constraint WHERE conname='" + constraint + "'") == 1;
    }

    private static boolean indexExists(Connection connection, String index) throws SQLException {
        return count(connection, "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname='" + index + "'") == 1;
    }
}