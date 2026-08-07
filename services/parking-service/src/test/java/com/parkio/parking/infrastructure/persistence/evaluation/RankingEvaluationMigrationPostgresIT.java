package com.parkio.parking.infrastructure.persistence.evaluation;

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
class RankingEvaluationMigrationPostgresIT {

    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_ranking_eval_migration_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Test
    void v35UpgradesToV36WithRollupTables() throws Exception {
        Flyway toV35 = flyway(MigrationVersion.fromVersion("35"));
        toV35.clean();
        toV35.migrate();

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("35");
            assertThat(tableExists(connection, "ranking_evaluations")).isTrue();
            assertThat(tableExists(connection, "ranking_evaluation_daily_rollups")).isFalse();
            assertThat(tableExists(connection, "ranking_evaluation_rollup_watermark")).isFalse();
        }

        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(1);

        try (Connection connection = openConnection()) {
            assertThat(currentFlywayVersion(connection)).isEqualTo("36");
            assertThat(tableExists(connection, "ranking_evaluations")).isTrue();
            assertThat(tableExists(connection, "ranking_evaluation_outcomes")).isTrue();
            assertThat(tableExists(connection, "ranking_evaluation_daily_rollups")).isTrue();
            assertThat(tableExists(connection, "ranking_evaluation_rollup_slices")).isTrue();
            assertThat(tableExists(connection, "ranking_evaluation_rollup_watermark")).isTrue();
            assertThat(indexExists(connection, "idx_ranking_evaluation_daily_rollups_date")).isTrue();
            assertThat(columnExists(connection, "ranking_evaluation_daily_rollups", "evaluation_id")).isFalse();
            assertThat(columnExists(connection, "ranking_evaluation_daily_rollups", "user_id")).isFalse();
            assertThat(columnExists(connection, "ranking_evaluation_daily_rollups", "facility_id")).isFalse();
            assertThat(columnExists(connection, "ranking_evaluation_daily_rollups", "session_id")).isFalse();
            assertThat(columnExists(connection, "ranking_evaluation_daily_rollups", "latitude")).isFalse();
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
                "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name=?")) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean indexExists(Connection connection, String indexName) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname=?")) {
            statement.setString(1, indexName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                """
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema='public' AND table_name=? AND column_name=?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
