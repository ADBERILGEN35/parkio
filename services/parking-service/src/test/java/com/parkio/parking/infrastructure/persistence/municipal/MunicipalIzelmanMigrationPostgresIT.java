package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.parkio.parking.testsupport.PostgisTestImages;

@Tag("integration")
@Testcontainers
class MunicipalIzelmanMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            PostgisTestImages.dockerImageName());

    @Test
    void migrationCreatesTablesAndSeedsExactlyOnce() throws Exception {
        Flyway flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        flyway.migrate();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(count(connection, "SELECT count(*) FROM municipal_data_sources WHERE family_key='izelman'"))
                    .isEqualTo(5);
            for (String table : new String[] {"municipal_roadside_segments", "municipal_roadside_source_links",
                    "municipal_tariff_plans", "municipal_tariff_rate_bands", "municipal_tariff_assignments",
                    "municipal_izelman_import_runs"}) {
                assertThat(count(connection, "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema='public' AND table_name='" + table + "'")).isEqualTo(1);
            }
        }
    }

    private static long count(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
