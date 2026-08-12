package com.parkio.parking.infrastructure.persistence.calibration;

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
class CalibrationShadowMigrationPostgresIT {

    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_calibration_migration_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Test
    void freshSchemaMigratesThroughV26WithExpectedCalibrationSchema() throws Exception {
        Flyway flyway = flyway(null);
        flyway.clean();
        flyway.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("37");
            assertThat(tableExists(connection, "calibration_observation")).isTrue();
            assertThat(tableExists(connection, "calibration_report")).isTrue();
            assertThat(tableExists(connection, "calibration_readiness_assessment")).isTrue();
            assertThat(uniqueConstraintExists(connection, "calibration_observation", "uq_calibration_observation_observation"))
                    .isTrue();
            assertThat(uniqueConstraintExists(connection, "calibration_report", "uq_calibration_report_report")).isTrue();
            assertThat(uniqueConstraintExists(
                            connection, "calibration_readiness_assessment", "uq_calibration_readiness_assessment_assessment"))
                    .isTrue();
            assertThat(indexExists(connection, "idx_calibration_observation_engine_predicted")).isTrue();
            assertThat(indexExists(connection, "idx_calibration_report_engine_policy_generated")).isTrue();
            assertThat(indexExists(connection, "idx_calibration_readiness_assessment_report")).isTrue();
        }
    }

    @Test
    void v25SchemaUpgradesCleanlyToV26() throws Exception {
        Flyway targetV25 = flyway(MigrationVersion.fromVersion("25"));
        targetV25.clean();
        targetV25.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("25");
            assertThat(tableExists(connection, "calibration_observation")).isFalse();
            assertThat(tableExists(connection, "calibration_report")).isFalse();
            assertThat(tableExists(connection, "calibration_readiness_assessment")).isFalse();
        }

        Flyway full = flyway(null);
        full.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("37");
            assertThat(tableExists(connection, "calibration_observation")).isTrue();
            assertThat(tableExists(connection, "calibration_report")).isTrue();
            assertThat(tableExists(connection, "calibration_readiness_assessment")).isTrue();
            assertThat(foreignKeyExists(
                            connection,
                            "calibration_readiness_assessment",
                            "fk_calibration_readiness_assessment_report"))
                    .isTrue();
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
}
