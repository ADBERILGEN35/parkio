package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.testsupport.PostgisTestImages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PROD-DEPLOY-01A-R8.6 — the preparation tool's public-schema census, executed as the operator tool
 * executes it, against a real PostgreSQL + PostGIS.
 *
 * <h2>The defect this exists to prevent recurring</h2>
 *
 * <p>R8.5's census asked one question: does this {@code pg_class} row carry a {@code deptype='e'}
 * dependency? PostGIS answers "no" for two objects it nevertheless owns — the composite types
 * {@code geometry_dump} and {@code valid_detail}, whose extension membership is recorded against
 * their {@code pg_type} rows — and for {@code spatial_ref_sys_pkey}, which belongs to the extension
 * only through the table it indexes. Live invite-production therefore came back with
 * {@code unexpectedObjects=2} and preparation was refused on a perfectly valid database.
 *
 * <p>Nothing caught it because the only coverage was
 * {@code scripts/test-managed-parking-flyway-baseline.sh}, which fakes {@code psql} and fed the
 * count in as {@code FAKE_UNEXPECTED=0}. The census SQL was the one part of the tool that no real
 * database had ever run. This suite closes that hole by executing
 * {@code scripts/azure/sql/managed-parking-public-census.sql} — the very file the shell script
 * runs, not a copy — against a live PostGIS catalog.
 *
 * <p>Runs against {@link PostgisTestImages} (repository default), and honours
 * {@code -Dparkio.postgis.image} so the same matrix can be re-proven against the
 * PostgreSQL 16 / PostGIS 3.6.1 live-parity image.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ManagedParkingPublicCensusIT {

    private static final String MIGRATION_ROLE = "parkio_parking_migrator";
    private static final String UNATTRIBUTED = "UNATTRIBUTED";
    private static final String POSTGIS = "extension:postgis";
    private static final String FLYWAY = "flyway";

    @Container
    static final PostgreSQLContainer<?> POSTGIS_DB =
            new PostgreSQLContainer<>(PostgisTestImages.dockerImageName())
                    .withDatabaseName("parkio_parking_census_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    /** The census SQL the operator tool ships — located, not duplicated. */
    private static String censusSql() throws IOException {
        Path relative = Path.of("scripts", "azure", "sql", "managed-parking-public-census.sql");
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("could not locate " + relative + " from the working directory");
    }

    // ------------------------------------------------------------------ fixtures

    private static String urlFor(String database) {
        return "jdbc:postgresql://%s:%d/%s"
                .formatted(POSTGIS_DB.getHost(), POSTGIS_DB.getMappedPort(5432), database);
    }

    private static Connection admin(String database) throws Exception {
        return DriverManager.getConnection(
                urlFor(database), POSTGIS_DB.getUsername(), POSTGIS_DB.getPassword());
    }

    private static void exec(String database, String... statements) throws Exception {
        try (Connection c = admin(database);
                Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    /** A database in the certified STATE B shape: PostGIS in public, empty Flyway history table. */
    private static String stateBDatabase(String name) throws Exception {
        try (Connection c = admin(POSTGIS_DB.getDatabaseName());
                Statement s = c.createStatement()) {
            s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"
                    + name + "'");
            s.execute("DROP DATABASE IF EXISTS " + name);
            s.execute("CREATE DATABASE " + name);
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
                    + MIGRATION_ROLE + "') THEN CREATE ROLE " + MIGRATION_ROLE
                    + " LOGIN PASSWORD 'census-it' NOSUPERUSER; END IF; END $$");
        }
        exec(name,
                "CREATE EXTENSION IF NOT EXISTS postgis",
                "ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE,
                """
                CREATE TABLE public.flyway_schema_history (
                    installed_rank integer NOT NULL,
                    version character varying(50),
                    description character varying(200) NOT NULL,
                    type character varying(20) NOT NULL,
                    script character varying(1000) NOT NULL,
                    checksum integer,
                    installed_by character varying(100) NOT NULL,
                    installed_on timestamp without time zone DEFAULT now() NOT NULL,
                    execution_time integer NOT NULL,
                    success boolean NOT NULL,
                    CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank)
                )
                """,
                "CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history (success)",
                "ALTER TABLE public.flyway_schema_history OWNER TO " + MIGRATION_ROLE);
        return name;
    }

    /** Run the shipped census and return {@code kind:name -> attribution}. */
    private static Map<String, String> census(String database) throws Exception {
        Map<String, String> rows = new LinkedHashMap<>();
        try (Connection c = admin(database);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(censusSql())) {
            while (rs.next()) {
                rows.put(rs.getString(1) + ":" + rs.getString(2), rs.getString(3));
            }
        }
        return rows;
    }

    private static List<String> unattributed(Map<String, String> census) {
        return census.entrySet().stream()
                .filter(e -> UNATTRIBUTED.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    // ------------------------------------------------------- certified STATE B

    @Test
    void certifiedStateBHasZeroUnattributedObjects() throws Exception {
        String db = stateBDatabase("census_state_b");
        Map<String, String> census = census(db);

        System.out.println("[R8.6][image] " + PostgisTestImages.imageReference());
        System.out.println("[R8.6][STATE B] censused=" + census.size()
                + " unattributed=" + unattributed(census));

        // The two objects that produced R8.5's false positive against live.
        assertThat(census).containsEntry("c:geometry_dump", POSTGIS);
        assertThat(census).containsEntry("c:valid_detail", POSTGIS);
        // Index attribution flows from the table it indexes.
        assertThat(census).containsEntry("i:spatial_ref_sys_pkey", POSTGIS);
        // Ordinary extension-owned relations and views.
        assertThat(census).containsEntry("r:spatial_ref_sys", POSTGIS);
        assertThat(census).containsEntry("v:geometry_columns", POSTGIS);
        assertThat(census).containsEntry("v:geography_columns", POSTGIS);
        // Extension base types.
        assertThat(census).containsEntry("type:geometry", POSTGIS);
        assertThat(census).containsEntry("type:geography", POSTGIS);
        // Flyway's own table plus the indexes that belong to it.
        assertThat(census).containsEntry("r:flyway_schema_history", FLYWAY);
        assertThat(census).containsEntry("i:flyway_schema_history_pk", FLYWAY);
        assertThat(census).containsEntry("i:flyway_schema_history_s_idx", FLYWAY);

        assertThat(unattributed(census))
                .as("the certified live-equivalent state must census clean")
                .isEmpty();
    }

    @Test
    void everyPostgisObjectIsPositivelyAttributed() throws Exception {
        String db = stateBDatabase("census_postgis_all");
        Map<String, String> census = census(db);

        // Nothing PostGIS installs may fall through to UNATTRIBUTED — including the several
        // hundred functions it puts in public.
        assertThat(census.values()).contains(POSTGIS);
        assertThat(census.entrySet().stream().filter(e -> e.getKey().startsWith("proc:")).count())
                .as("PostGIS installs its routines into public")
                .isGreaterThan(100L);
        assertThat(unattributed(census)).isEmpty();
    }

    // ------------------------------------------------------- negative matrix (§6)

    /**
     * Every class of object the census must still refuse to explain. Composite types are the
     * important ones: the fix must attribute PostGIS's composites without blanket-ignoring
     * {@code relkind='c'}, or an unknown composite type would slip through.
     */
    @Test
    void unknownObjectsOfEveryKindAreDetected() throws Exception {
        String db = stateBDatabase("census_negative");
        exec(db,
                "CREATE TABLE public.parkio_spots_unknown (id bigint PRIMARY KEY)",          // A
                "CREATE VIEW public.unknown_view AS SELECT 1 AS x",                          // B
                "CREATE SEQUENCE public.unknown_seq",                                        // C
                "CREATE TYPE public.unknown_composite AS (a int, b text)",                   // D
                "CREATE INDEX unknown_idx ON public.parkio_spots_unknown (id)",              // E
                "CREATE TABLE public.parking_spots (id bigint PRIMARY KEY)",                 // F
                "CREATE DOMAIN public.unknown_domain AS text",                               // G
                "CREATE TYPE public.unknown_enum AS ENUM ('a','b')",                         // G
                "CREATE TYPE public.geometry_dump_lookalike AS (path int[], geom int)",      // H
                "CREATE FUNCTION public.st_fake_helper() RETURNS int LANGUAGE sql AS 'SELECT 1'"); // H

        List<String> flagged = unattributed(census(db));
        System.out.println("[R8.6][negative] " + flagged);

        assertThat(flagged)
                .contains(
                        "r:parkio_spots_unknown",        // A unknown ordinary table
                        "v:unknown_view",                // B unknown view
                        "S:unknown_seq",                 // C unknown sequence
                        "c:unknown_composite",           // D unknown composite type
                        "i:unknown_idx",                 // E unknown index
                        "r:parking_spots",               // F Parkio application table
                        "type:unknown_domain",           // G domain with no extension owner
                        "type:unknown_enum",             // G enum with no extension owner
                        "c:geometry_dump_lookalike",     // H PostGIS-like name, not owned
                        "proc:st_fake_helper");          // H PostGIS-like routine, not owned
    }

    /**
     * A composite type that PostGIS really owns and one that merely looks like it must land on
     * opposite sides in the same census — the precise distinction R8.5 could not make.
     */
    @Test
    void ownedAndLookalikeCompositeTypesAreSeparated() throws Exception {
        String db = stateBDatabase("census_lookalike");
        exec(db, "CREATE TYPE public.valid_detail_lookalike AS (valid bool, reason varchar)");

        Map<String, String> census = census(db);
        assertThat(census).containsEntry("c:valid_detail", POSTGIS);
        assertThat(census).containsEntry("c:valid_detail_lookalike", UNATTRIBUTED);
    }

    /**
     * An index on the Flyway history table is attributed to Flyway however it is named — it is
     * dropped with the table, so it must not block preparation.
     */
    @Test
    void anyIndexOnTheFlywayTableIsAttributedToFlyway() throws Exception {
        String db = stateBDatabase("census_flyway_idx");
        exec(db, "CREATE INDEX oddly_named_idx ON public.flyway_schema_history (version)");

        Map<String, String> census = census(db);
        assertThat(census).containsEntry("i:oddly_named_idx", FLYWAY);
        assertThat(unattributed(census)).isEmpty();
    }

    /** A sequence owned by an unattributed table stays unattributed along with its table. */
    @Test
    void identitySequenceFollowsItsTable() throws Exception {
        String db = stateBDatabase("census_identity");
        exec(db, "CREATE TABLE public.parkio_serial_unknown (id bigserial PRIMARY KEY)");

        List<String> flagged = unattributed(census(db));
        assertThat(flagged).contains("r:parkio_serial_unknown", "S:parkio_serial_unknown_id_seq");
    }
}
