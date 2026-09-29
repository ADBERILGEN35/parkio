package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.parkio.parking.testsupport.PostgisTestImages;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class MunicipalSourceMigrationPostgresIT {

    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_municipal_migration_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Test
    void freshSchemaMigratesthroughV36WithMunicipalTablesAndSeeds() throws Exception {
        Flyway flyway = flyway(null);
        flyway.clean();
        flyway.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("40");
            assertThat(tableExists(connection, "municipal_data_sources")).isTrue();
            assertThat(tableExists(connection, "municipal_source_sync_runs")).isTrue();
            assertThat(tableExists(connection, "municipal_parking_facilities")).isTrue();
            assertThat(tableExists(connection, "municipal_facility_source_links")).isTrue();
            assertThat(tableExists(connection, "municipal_occupancy_snapshots")).isTrue();
            assertThat(uniqueConstraintExists(connection, "municipal_facility_source_links",
                    "uq_municipal_facility_source_links_source_ext")).isTrue();
            assertThat(uniqueConstraintExists(connection, "municipal_occupancy_snapshots",
                    "uq_municipal_occupancy_snapshots_dedupe")).isTrue();
            assertThat(indexExists(connection, "idx_municipal_parking_facilities_location")).isTrue();
            assertThat(indexExists(connection, "uq_municipal_source_sync_runs_one_running")).isTrue();
            assertThat(seedCount(connection, "izmir-izum-otoparklar")).isEqualTo(1);
                assertThat(seedCount(connection, "izelman-open-parking-facilities")).isEqualTo(1);
            assertThat(seedCount(connection, "osm-geofabrik-turkey")).isEqualTo(1);
            assertThat(seedCount(connection, "istanbul-ispark-parks")).isEqualTo(1);
            assertThat(seedCount(connection, "ankara-anpark-parks")).isEqualTo(1);
            assertThat(seedCount(connection, "konya-bb-otopark-bilgileri")).isEqualTo(1);
            assertThat(seedCount(connection, "kayseri-bb-otoparklar")).isEqualTo(1);
            assertThat(tableExists(connection, "municipal_osm_import_runs")).isTrue();
            assertThat(tableExists(connection, "municipal_facility_conflation_decisions")).isTrue();
            assertThat(tableExists(connection, "municipal_link_candidate_generation_runs")).isTrue();
            assertThat(indexExists(connection, "uq_mlcg_runs_one_running")).isTrue();
            assertThat(tableExists(connection, "ranking_evaluations")).isTrue();
            assertThat(tableExists(connection, "ranking_evaluation_outcomes")).isTrue();

            flyway.migrate();
            assertThat(currentFlywayVersion(connection)).isEqualTo("40");
            assertThat(seedCount(connection, "izmir-izum-otoparklar")).isEqualTo(1);
                assertThat(seedCount(connection, "izelman-open-parking-facilities")).isEqualTo(1);
            assertThat(seedCount(connection, "osm-geofabrik-turkey")).isEqualTo(1);
            assertThat(seedCount(connection, "istanbul-ispark-parks")).isEqualTo(1);
            assertThat(seedCount(connection, "ankara-anpark-parks")).isEqualTo(1);
            assertThat(seedCount(connection, "konya-bb-otopark-bilgileri")).isEqualTo(1);
            assertThat(seedCount(connection, "kayseri-bb-otoparklar")).isEqualTo(1);
        }
    }

    @Test
    void v27SchemaUpgradesCleanlyToV36() throws Exception {
        Flyway targetV27 = flyway(MigrationVersion.fromVersion("27"));
        targetV27.clean();
        targetV27.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("27");
            assertThat(tableExists(connection, "municipal_data_sources")).isFalse();
        }

        Flyway full = flyway(null);
        full.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("40");
            assertThat(tableExists(connection, "municipal_data_sources")).isTrue();
            assertThat(foreignKeyExists(connection, "municipal_facility_source_links",
                    "fk_municipal_facility_source_links_facility")).isTrue();
            assertThat(foreignKeyExists(connection, "municipal_occupancy_snapshots",
                    "fk_municipal_occupancy_snapshots_facility")).isTrue();
            assertThat(tableExists(connection, "municipal_link_candidate_generation_runs")).isTrue();
            assertThat(seedCount(connection, "izmir-izum-otoparklar")).isEqualTo(1);
                assertThat(seedCount(connection, "izelman-open-parking-facilities")).isEqualTo(1);
            assertThat(seedCount(connection, "istanbul-ispark-parks")).isEqualTo(1);
            assertThat(seedCount(connection, "ankara-anpark-parks")).isEqualTo(1);
            assertThat(seedCount(connection, "konya-bb-otopark-bilgileri")).isEqualTo(1);
            assertThat(seedCount(connection, "kayseri-bb-otoparklar")).isEqualTo(1);
        }
    }

    private static Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String currentFlywayVersion(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?")) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private static boolean uniqueConstraintExists(Connection connection, String tableName, String constraintName)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                """
                SELECT count(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'UNIQUE'
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, constraintName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private static boolean foreignKeyExists(Connection connection, String tableName, String constraintName)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                """
                SELECT count(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'FOREIGN KEY'
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, constraintName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private static boolean indexExists(Connection connection, String indexName) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?")) {
            statement.setString(1, indexName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private static long seedCount(Connection connection, String sourceKey) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT count(*) FROM municipal_data_sources WHERE source_key = ?")) {
            statement.setString(1, sourceKey);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}