package com.parkio.parking.infrastructure.persistence.reward;

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
class RewardShadowMigrationPostgresIT {

    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_reward_migration_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Test
    void freshSchemaMigratesThroughV24WithExpectedRewardSchema() throws Exception {
        Flyway flyway = flyway(null);
        flyway.clean();
        flyway.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("37");
            assertThat(tableExists(connection, "pending_reward_ledger")).isTrue();
            assertThat(uniqueConstraintExists(connection, "pending_reward_ledger", "uq_pending_reward_ledger_evaluation"))
                    .isTrue();
            assertThat(uniqueConstraintExists(connection, "pending_reward_ledger", "uq_pending_reward_ledger_contribution"))
                    .isTrue();
            assertThat(indexExists(connection, "idx_pending_reward_ledger_subject_evaluated")).isTrue();
            assertThat(indexExists(connection, "idx_pending_reward_ledger_outcome")).isTrue();
            assertThat(indexExists(connection, "idx_pending_reward_ledger_spot_role")).isTrue();
        }
    }

    @Test
    void v23SchemaUpgradesCleanlyToV24() throws Exception {
        Flyway targetV23 = flyway(MigrationVersion.fromVersion("23"));
        targetV23.clean();
        targetV23.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("23");
            assertThat(tableExists(connection, "pending_reward_ledger")).isFalse();
        }

        Flyway full = flyway(null);
        full.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("37");
            assertThat(tableExists(connection, "pending_reward_ledger")).isTrue();
            assertThat(foreignKeyExists(connection, "pending_reward_ledger", "fk_pending_reward_ledger_outcome")).isTrue();
            assertThat(foreignKeyExists(connection, "pending_reward_ledger", "fk_pending_reward_ledger_spot")).isTrue();
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
