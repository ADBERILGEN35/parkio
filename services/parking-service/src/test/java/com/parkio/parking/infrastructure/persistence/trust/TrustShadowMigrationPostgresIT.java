package com.parkio.parking.infrastructure.persistence.trust;

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
class TrustShadowMigrationPostgresIT {

    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_trust_migration_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Test
    void freshSchemaMigratesThroughV23WithExpectedTrustSchema() throws Exception {
        Flyway flyway = flyway(null);
        flyway.clean();

        flyway.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("37");
            assertThat(tableExists(connection, "trust_ledger")).isTrue();
            assertThat(tableExists(connection, "trust_snapshot")).isTrue();
            assertThat(columnExists(connection, "trust_snapshot", "version")).isTrue();
            assertThat(uniqueConstraintExists(connection, "trust_ledger", "uq_trust_ledger_evaluation")).isTrue();
            assertThat(uniqueConstraintExists(connection, "trust_ledger", "uq_trust_ledger_evidence")).isTrue();
            assertThat(uniqueConstraintExists(connection, "trust_snapshot", "uq_trust_snapshot_subject_domain")).isTrue();
            assertThat(indexExists(connection, "idx_trust_ledger_subject_domain_evaluated")).isTrue();
            assertThat(indexExists(connection, "idx_trust_ledger_outcome")).isTrue();
            assertThat(indexExists(connection, "idx_trust_snapshot_subject_domain")).isTrue();
        }
    }

    @Test
    void v22SchemaUpgradesCleanlyToV23() throws Exception {
        Flyway targetV22 = flyway(MigrationVersion.fromVersion("22"));
        targetV22.clean();
        targetV22.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("22");
            assertThat(tableExists(connection, "trust_ledger")).isFalse();
            assertThat(tableExists(connection, "trust_snapshot")).isFalse();
        }

        Flyway full = flyway(null);
        full.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("37");
            assertThat(tableExists(connection, "trust_ledger")).isTrue();
            assertThat(tableExists(connection, "trust_snapshot")).isTrue();
            assertThat(foreignKeyExists(connection, "trust_ledger", "fk_trust_ledger_outcome")).isTrue();
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
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?");
                ) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (var statement = connection.prepareStatement(
                        """
                        SELECT count(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                        """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
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
