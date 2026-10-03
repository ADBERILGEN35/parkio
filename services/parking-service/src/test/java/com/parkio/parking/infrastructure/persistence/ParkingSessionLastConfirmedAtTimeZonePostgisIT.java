package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.testsupport.PostgisTestImages;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * CL-F38a: {@code parking_sessions.last_confirmed_at} arrived in V17 as TIMESTAMP without a
 * time zone. parking-service writes it as a UTC wall clock ({@code hibernate.jdbc.time_zone:
 * UTC}), but V17 back-filled ACTIVE rows from {@code started_at} (TIMESTAMPTZ) through the
 * session time zone, and SQL that compares it with TIMESTAMPTZ values also goes through the
 * session zone. After the migrations, the column must hold the instants the rows mean in any
 * session zone: the back-filled value equals {@code started_at}, an application-written value
 * is unchanged, and the stale-session cut-off selects the same rows in UTC and Istanbul.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ParkingSessionLastConfirmedAtTimeZonePostgisIT {

    private static final String NON_UTC_ZONE = "Europe/Istanbul";
    private static final Instant BACKFILLED_STARTED_AT = Instant.parse("2026-07-21T09:00:00Z");
    private static final Instant APP_STARTED_AT = Instant.parse("2026-07-21T08:00:00Z");
    private static final Instant APP_CONFIRMED_AT = Instant.parse("2026-07-21T10:15:00Z");
    private static final Instant STALE_CUTOFF = Instant.parse("2026-07-21T09:30:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(PostgisTestImages.dockerImageName())
            .withDatabaseName("parkio_parking_tz_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Test
    void lastConfirmedAtMeansTheSameInstantInEverySessionZone() {
        UUID backfilled = UUID.randomUUID();
        UUID appWritten = UUID.randomUUID();
        try (Session istanbul = new Session(NON_UTC_ZONE)) {
            migrate("16");
            insertActiveSession(istanbul.jdbc, backfilled, BACKFILLED_STARTED_AT);
            // V17 adds the column and back-fills ACTIVE rows from started_at in this session zone.
            migrate("17");
            insertActiveSession(istanbul.jdbc, appWritten, APP_STARTED_AT);
            // What Hibernate writes for Instant 10:15Z with hibernate.jdbc.time_zone=UTC.
            istanbul.jdbc.update("UPDATE parking_sessions SET last_confirmed_at = ?::timestamp WHERE id = ?",
                    "2026-07-21 10:15:00", appWritten);
            migrate(null);
        }

        // Soft assertions: every mismatch is reported, not just the first one.
        SoftAssertions softly = new SoftAssertions();
        try (Session utc = new Session("UTC"); Session istanbul = new Session(NON_UTC_ZONE)) {
            for (Session session : List.of(utc, istanbul)) {
                softly.assertThat(lastConfirmedAt(session.jdbc, backfilled))
                        .as("V17 back-filled row in a %s session", session.zone).isEqualTo(BACKFILLED_STARTED_AT);
                softly.assertThat(lastConfirmedAt(session.jdbc, appWritten))
                        .as("application-written row in a %s session", session.zone).isEqualTo(APP_CONFIRMED_AT);
                softly.assertThat(session.jdbc.queryForList("""
                        SELECT id FROM parking_sessions
                        WHERE status = 'ACTIVE' AND last_confirmed_at <= ?::timestamptz
                        """, UUID.class, STALE_CUTOFF.toString()))
                        .as("stale cut-off in a %s session", session.zone)
                        .containsExactly(backfilled);
            }
            softly.assertThat(utc.jdbc.queryForObject("""
                    SELECT data_type FROM information_schema.columns
                    WHERE table_name = 'parking_sessions' AND column_name = 'last_confirmed_at'
                    """, String.class)).as("column type").isEqualTo("timestamp with time zone");
        }
        softly.assertAll();
    }

    private static Instant lastConfirmedAt(JdbcTemplate jdbc, UUID id) {
        // Read as an absolute instant regardless of the column type or session zone.
        return jdbc.queryForObject("""
                SELECT to_char(last_confirmed_at::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                FROM parking_sessions WHERE id = ?
                """, (rs, rowNum) -> Instant.parse(rs.getString(1)), id);
    }

    private static void insertActiveSession(JdbcTemplate jdbc, UUID id, Instant startedAt) {
        jdbc.update("""
                INSERT INTO parking_sessions
                    (id, user_id, status, parking_source, started_at, latitude, longitude, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 'MANUAL', ?, 41.0082, 28.9784, ?, ?)
                """, id, UUID.randomUUID(), Timestamp.from(startedAt), Timestamp.from(startedAt),
                Timestamp.from(startedAt));
    }

    private static void migrate(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration")
                .initSql("SET TIME ZONE '" + NON_UTC_ZONE + "'");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    /** One connection pinned to a session time zone. */
    private static final class Session implements AutoCloseable {
        private final String zone;
        private final SingleConnectionDataSource dataSource;
        private final JdbcTemplate jdbc;

        Session(String zone) {
            this.zone = zone;
            this.dataSource = new SingleConnectionDataSource(
                    POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword(), true);
            this.jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("SET TIME ZONE '" + zone + "'");
        }

        @Override
        public void close() {
            dataSource.destroy();
        }
    }
}
