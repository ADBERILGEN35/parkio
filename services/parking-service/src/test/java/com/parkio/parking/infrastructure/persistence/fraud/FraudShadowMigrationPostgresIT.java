package com.parkio.parking.infrastructure.persistence.fraud;

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
class FraudShadowMigrationPostgresIT {

    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_fraud_migration_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Test
    void freshSchemaMigratesThroughV25WithExpectedFraudSchema() throws Exception {
        Flyway flyway = flyway(MigrationVersion.fromVersion("25"));
        flyway.clean();
        flyway.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("25");
            assertThat(tableExists(connection, "fraud_evaluation_ledger")).isTrue();
            assertThat(uniqueConstraintExists(connection, "fraud_evaluation_ledger", "uq_fraud_evaluation_ledger_evaluation")).isTrue();
            assertThat(uniqueConstraintExists(connection, "fraud_evaluation_ledger", "uq_fraud_evaluation_ledger_trigger")).isTrue();
            assertThat(checkConstraintExists(connection, "fraud_evaluation_ledger", "ck_fraud_evaluation_ledger_risk_bounded")).isTrue();
            assertThat(indexExists(connection, "idx_fraud_evaluation_ledger_subject_domain_evaluated")).isTrue();
            assertThat(indexExists(connection, "idx_fraud_evaluation_ledger_outcome")).isTrue();
        }
    }

    @Test
    void v24SchemaUpgradesCleanlyToV25() throws Exception {
        Flyway targetV24 = flyway(MigrationVersion.fromVersion("24"));
        targetV24.clean();
        targetV24.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("24");
            assertThat(tableExists(connection, "fraud_evaluation_ledger")).isFalse();
        }

        Flyway full = flyway(null);
        full.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("35");
            assertThat(tableExists(connection, "fraud_evaluation_ledger")).isTrue();
            assertThat(foreignKeyExists(connection, "fraud_evaluation_ledger", "fk_fraud_evaluation_ledger_outcome")).isTrue();
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

    private static boolean checkConstraintExists(Connection connection, String tableName, String constraintName)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                        """
                        SELECT count(*)
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name = ?
                          AND constraint_name = ?
                          AND constraint_type = 'CHECK'
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
