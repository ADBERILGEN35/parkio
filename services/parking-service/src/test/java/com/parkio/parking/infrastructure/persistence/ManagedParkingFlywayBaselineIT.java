package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.testsupport.PostgisTestImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PROD-DEPLOY-01A-R8.5 — managed parking Flyway baseline contract, proven against a real
 * PostgreSQL + PostGIS with a genuinely unprivileged migration role.
 *
 * <h2>Why vanilla PostgreSQL is not enough on its own</h2>
 *
 * <p>Two different privilege gates are in play, and only one of them exists upstream:
 *
 * <ol>
 *   <li><b>Native.</b> {@code postgis} is not a trusted extension, so a non-superuser cannot
 *       {@code CREATE EXTENSION postgis} on a database where it is absent. Real on any PostgreSQL,
 *       proven unemulated by {@link #migrationRoleCannotCreatePostgisWhereItIsAbsent()}.
 *   <li><b>Azure.</b> Flexible Server adds a {@code ProcessUtility} hook that rejects the
 *       {@code CREATE EXTENSION} statement for any role outside {@code azure_pg_admin} — and that
 *       hook fires <em>before</em> {@code CreateExtension()} reaches its {@code IF NOT EXISTS}
 *       short-circuit. Upstream PostgreSQL returns the "already exists, skipping" NOTICE before it
 *       ever checks privileges, so on vanilla PostgreSQL {@code V1__enable_postgis.sql} silently
 *       <em>succeeds</em> for an unprivileged role once the extension is installed.
 * </ol>
 *
 * <p>That asymmetry is the trap this suite exists to close: a Testcontainers run that models only
 * gate (1) would show V1 passing in exactly the states where invite-production crash-looped, and
 * would certify an unsafe mechanism as safe. {@link #installAzureExtensionGuard} therefore installs
 * a {@code ddl_command_start} event trigger that reproduces gate (2) — including its firing order —
 * and raises Azure's verbatim message. Managed-profile states run under it; owner-environment
 * states (hosted-beta, local) do not, because no such hook exists there.
 *
 * <p>One more confound worth naming: the guard's own function must live outside {@code public}.
 * Left there it makes the schema non-empty and silently decides the very emptiness question the
 * suite is measuring — which is how an earlier revision of this file "proved" that
 * {@code baselineOnMigrate} engages when it does not.
 *
 * <h2>What it found</h2>
 *
 * <p>{@code baselineOnMigrate} engages only for a non-empty schema with no history table, and
 * Flyway's emptiness check <em>excludes extension-owned objects</em>. A managed {@code public}
 * holding nothing but PostGIS therefore reads as empty, and the flag never fires in <em>any</em>
 * live-relevant state — A, B or C. The explicit {@link Flyway#baseline()} operation does the job in
 * every one of them, needing only that the pre-existing empty history table be dropped first, which
 * is what Flyway's own refusal message instructs.
 *
 * <h2>Why V1 is not rewritten</h2>
 *
 * <p>{@code V1__enable_postgis.sql} is already applied, with its checksum recorded, on every
 * environment whose database owner can install the extension. Editing the file would fail their
 * {@code validate()}. The managed profile must therefore <em>skip</em> V1, never redefine it;
 * {@link ParkingMigrationV1ImmutabilityTest} pins the bytes and STATE E pins the owner path.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ManagedParkingFlywayBaselineIT {

    private static final String MIGRATION_ROLE = "parkio_parking_migrator_it";
    private static final String MIGRATION_PASSWORD = "migrator-it-pw";
    private static final String RUNTIME_ROLE = "parkio_parking_it";
    private static final String RUNTIME_PASSWORD = "runtime-it-pw";
    private static final String LOCATIONS = "classpath:db/migration";
    private static final int EXPECTED_HEAD = 40;

    /** Verbatim from the R7/R8 invite-production crash loop. */
    private static final String AZURE_GUARD_MESSAGE =
            "Because postgis isn't a trusted extension, only members of \"azure_pg_admin\" "
                    + "are allowed to use CREATE EXTENSION postgis";

    @Container
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(PostgisTestImages.dockerImageName())
                    .withDatabaseName("parkio_parking_baseline_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    // ------------------------------------------------------------------ SQL plumbing

    private static String urlFor(String database) {
        return "jdbc:postgresql://%s:%d/%s"
                .formatted(POSTGIS.getHost(), POSTGIS.getMappedPort(5432), database);
    }

    private static void adminExec(String database, String... sql) throws Exception {
        try (Connection c = DriverManager.getConnection(
                        urlFor(database), POSTGIS.getUsername(), POSTGIS.getPassword());
                Statement s = c.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        }
    }

    private static List<String> historyRows(String database) throws Exception {
        List<String> rows = new ArrayList<>();
        if (!tableExists(database, "flyway_schema_history")) {
            return rows;
        }
        try (Connection c = DriverManager.getConnection(
                        urlFor(database), POSTGIS.getUsername(), POSTGIS.getPassword());
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT installed_rank, coalesce(version,'-'), type, success"
                                + " FROM flyway_schema_history ORDER BY installed_rank")) {
            while (rs.next()) {
                rows.add("%d|%s|%s|%s"
                        .formatted(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
            }
        }
        return rows;
    }

    private static boolean tableExists(String database, String table) throws Exception {
        try (Connection c = DriverManager.getConnection(
                        urlFor(database), POSTGIS.getUsername(), POSTGIS.getPassword());
                Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery("SELECT to_regclass('public." + table + "') IS NOT NULL")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static void ensureRoles() throws Exception {
        try (Connection c = DriverManager.getConnection(
                        urlFor(POSTGIS.getDatabaseName()),
                        POSTGIS.getUsername(),
                        POSTGIS.getPassword());
                Statement s = c.createStatement()) {
            // The role name Azure gates extension creation on. Nothing is ever made a member of it:
            // its whole purpose is to be the membership the migration role provably lacks.
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname"
                    + " = 'azure_pg_admin') THEN CREATE ROLE azure_pg_admin NOLOGIN; END IF; END $$");
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
                    + MIGRATION_ROLE + "') THEN CREATE ROLE " + MIGRATION_ROLE
                    + " LOGIN PASSWORD '" + MIGRATION_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION; END IF; END $$");
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
                    + RUNTIME_ROLE + "') THEN CREATE ROLE " + RUNTIME_ROLE
                    + " LOGIN PASSWORD '" + RUNTIME_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION; END IF; END $$");
        }
    }

    /** Recreate {@code name} from scratch, dropping any leftover from an earlier run. */
    private static void recreateDatabase(String name) throws Exception {
        ensureRoles();
        try (Connection c = DriverManager.getConnection(
                        urlFor(POSTGIS.getDatabaseName()),
                        POSTGIS.getUsername(),
                        POSTGIS.getPassword());
                Statement s = c.createStatement()) {
            s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"
                    + name + "'");
            s.execute("DROP DATABASE IF EXISTS " + name);
            s.execute("CREATE DATABASE " + name);
        }
    }

    /**
     * Apply exactly the grants {@code scripts/azure/bootstrap-invite-production-databases.sh}
     * applies: the migration role owns database and {@code public}; the runtime role gets DML only.
     */
    private static void applyBootstrapGrants(String database) throws Exception {
        adminExec(database,
                "ALTER DATABASE " + database + " OWNER TO " + MIGRATION_ROLE,
                "REVOKE ALL ON DATABASE " + database + " FROM PUBLIC",
                "GRANT CONNECT ON DATABASE " + database + " TO " + RUNTIME_ROLE,
                "GRANT CONNECT, TEMPORARY ON DATABASE " + database + " TO " + MIGRATION_ROLE,
                "ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE,
                "REVOKE CREATE ON SCHEMA public FROM PUBLIC",
                "GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
    }

    /**
     * Reproduce Azure Flexible Server's extension gate. A {@code ddl_command_start} event trigger
     * fires from {@code ProcessUtilitySlow} before the {@code CREATE EXTENSION} statement is
     * dispatched, i.e. before upstream's {@code IF NOT EXISTS} short-circuit — the same ordering as
     * Azure's utility hook, which is precisely why {@code IF NOT EXISTS} does not save V1 there.
     */
    private static void installAzureExtensionGuard(String database) throws Exception {
        adminExec(database,
                // Deliberately NOT in public: a guard object sitting in the Flyway schema would
                // make it non-empty and silently decide the very emptiness question under test.
                "CREATE SCHEMA parkio_azure_guard",
                """
                CREATE FUNCTION parkio_azure_guard.extension_guard() RETURNS event_trigger
                LANGUAGE plpgsql AS $guard$
                BEGIN
                    IF current_setting('is_superuser') = 'on'
                       OR pg_has_role(current_user, 'azure_pg_admin', 'MEMBER') THEN
                        RETURN;
                    END IF;
                    RAISE EXCEPTION 'Because postgis isn''t a trusted extension, only members of "azure_pg_admin" are allowed to use CREATE EXTENSION postgis';
                END
                $guard$
                """,
                "CREATE EVENT TRIGGER parkio_azure_extension_guard ON ddl_command_start"
                        + " WHEN TAG IN ('CREATE EXTENSION')"
                        + " EXECUTE FUNCTION parkio_azure_guard.extension_guard()");
    }

    /**
     * A database shaped like managed invite-production: PostGIS pre-provisioned in {@code public}
     * by the administrator, least-privilege roles in place, Azure's extension gate armed.
     */
    private static String managedDatabase(String name) throws Exception {
        recreateDatabase(name);
        adminExec(name,
                "CREATE EXTENSION IF NOT EXISTS postgis",
                "GRANT SELECT ON TABLE spatial_ref_sys TO " + RUNTIME_ROLE,
                "GRANT SELECT ON TABLE spatial_ref_sys TO " + MIGRATION_ROLE);
        applyBootstrapGrants(name);
        installAzureExtensionGuard(name);
        return name;
    }

    private static Flyway flyway(String database, String user, String password, boolean baselineOnMigrate) {
        return Flyway.configure()
                .dataSource(urlFor(database), user, password)
                .locations(LOCATIONS)
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("1")
                .baselineDescription("PostGIS preprovisioned by infrastructure bootstrap")
                .load();
    }

    private static Flyway asMigrationRole(String database, boolean baselineOnMigrate) {
        return flyway(database, MIGRATION_ROLE, MIGRATION_PASSWORD, baselineOnMigrate);
    }

    // ------------------------------------------------------ §3/§7 privilege model

    /**
     * Native, unemulated: {@code postgis} is untrusted, so the migration role cannot install it even
     * though it owns the database and the {@code public} schema. Ownership is not the constraint —
     * extension trust is.
     */
    @Test
    void migrationRoleCannotCreatePostgisWhereItIsAbsent() throws Exception {
        String db = "state_privilege_native";
        recreateDatabase(db);
        applyBootstrapGrants(db);

        Throwable failure = catchThrowable(() -> {
            try (Connection c = DriverManager.getConnection(
                            urlFor(db), MIGRATION_ROLE, MIGRATION_PASSWORD);
                    Statement s = c.createStatement()) {
                s.execute("CREATE EXTENSION postgis");
            }
        });

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).contains("permission denied");
        record("PRIVILEGE/native", "CREATE EXTENSION postgis as migration role -> " + firstLine(failure));
    }

    /**
     * The decisive asymmetry. On upstream PostgreSQL {@code IF NOT EXISTS} short-circuits before the
     * privilege check, so V1's exact statement <em>succeeds</em> for the unprivileged role once the
     * extension exists — which is why an unguarded Testcontainers run cannot certify this mechanism.
     * Under Azure's gate the same statement is rejected.
     */
    @Test
    void ifNotExistsSucceedsUpstreamButIsRejectedUnderTheAzureGate() throws Exception {
        String unguarded = "state_ifnotexists_upstream";
        recreateDatabase(unguarded);
        adminExec(unguarded, "CREATE EXTENSION IF NOT EXISTS postgis");
        applyBootstrapGrants(unguarded);

        Throwable upstream = catchThrowable(() -> {
            try (Connection c = DriverManager.getConnection(
                            urlFor(unguarded), MIGRATION_ROLE, MIGRATION_PASSWORD);
                    Statement s = c.createStatement()) {
                s.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            }
        });
        assertThat(upstream)
                .as("upstream PostgreSQL returns 'already exists, skipping' before checking privilege"
                        + " — this is exactly the false negative the Azure gate emulation exists to"
                        + " prevent")
                .isNull();

        String guarded = managedDatabase("state_ifnotexists_azure");
        Throwable azure = catchThrowable(() -> {
            try (Connection c = DriverManager.getConnection(
                            urlFor(guarded), MIGRATION_ROLE, MIGRATION_PASSWORD);
                    Statement s = c.createStatement()) {
                s.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            }
        });
        assertThat(azure).isNotNull();
        assertThat(azure.getMessage()).contains(AZURE_GUARD_MESSAGE);
        record("PRIVILEGE/azure-gate",
                "upstream IF NOT EXISTS -> no-op success; guarded -> " + firstLine(azure));
    }

    // -------------------------------------------------- §4 baselineOnMigrate evidence

    /**
     * STATE A — a genuinely empty Flyway schema: PostGIS lives in {@code parkio_ext}, off the
     * migration role's {@code search_path}, so {@code public} holds nothing at all.
     *
     * <p>{@code baselineOnMigrate} applies to a non-empty schema with no history table. An empty
     * schema is not a baseline candidate, so Flyway creates the history table and runs from V1 —
     * straight into the extension gate.
     */
    @Test
    void stateA_emptyFlywaySchema_baselineOnMigrateDoesNotEngage() throws Exception {
        String db = "state_a";
        recreateDatabase(db);
        adminExec(db,
                "CREATE SCHEMA parkio_ext",
                "CREATE EXTENSION IF NOT EXISTS postgis SCHEMA parkio_ext");
        applyBootstrapGrants(db);
        installAzureExtensionGuard(db);

        assertThat(tableExists(db, "flyway_schema_history")).isFalse();
        assertThat(tableExists(db, "spatial_ref_sys")).isFalse();

        Throwable failure = catchThrowable(() -> asMigrationRole(db, true).migrate());
        List<String> history = historyRows(db);
        record("STATE A", outcome(failure) + " | baselineRow=" + hasBaseline(history)
                + " | history=" + history);

        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains(AZURE_GUARD_MESSAGE);
        assertThat(hasBaseline(history)).isFalse();
        assertContractHolds("STATE A", history);
    }

    /**
     * STATE C — the live physical shape: PostGIS installed <em>in</em> {@code public}, so the schema
     * holds {@code spatial_ref_sys} and the PostGIS views and nothing else. No history table.
     *
     * <p>This is the state the shipped {@code SPRING_FLYWAY_BASELINE_ON_MIGRATE=true} configuration
     * was written for, and it is the state that disproves it. Flyway's emptiness check <em>excludes
     * extension-owned objects</em>, so a database whose {@code public} contains only PostGIS still
     * reads as empty — {@code baselineOnMigrate} never engages, and V1 is attempted against the
     * extension gate exactly as if the flag were absent.
     *
     * <p>Note what this run leaves behind: the history table, created before the first migration,
     * with the failed V1 row rolled back. That residue is the live STATE B, so the configuration
     * does not merely fail to help — it manufactures the state it then cannot recover from.
     */
    @Test
    void stateC_postgisOwnedObjectsDoNotMakeTheSchemaNonEmpty() throws Exception {
        String db = managedDatabase("state_c");
        assertThat(tableExists(db, "flyway_schema_history")).isFalse();
        assertThat(tableExists(db, "spatial_ref_sys")).isTrue();

        Throwable failure = catchThrowable(() -> asMigrationRole(db, true).migrate());
        List<String> history = historyRows(db);
        record("STATE C", outcome(failure) + " | baselineRow=" + hasBaseline(history)
                + " | history=" + history);

        assertThat(failure)
                .as("the shipped managed configuration still reaches V1")
                .isNotNull();
        assertThat(rootMessage(failure)).contains(AZURE_GUARD_MESSAGE);
        assertThat(hasBaseline(history))
                .as("PostGIS-owned objects do not count towards Flyway's emptiness verdict, so"
                        + " baselineOnMigrate does not engage")
                .isFalse();
        assertContractHolds("STATE C", history);

        assertThat(tableExists(db, "flyway_schema_history"))
                .as("and the attempt leaves the empty history table that becomes STATE B")
                .isTrue();
        assertThat(historyRows(db)).isEmpty();
    }

    /**
     * Produce the live invite-production state with real Flyway operations only: run the shipped
     * managed configuration once against a bootstrapped managed database. Flyway creates the
     * history table, attempts V1, the extension gate rejects it, and — PostgreSQL DDL being
     * transactional — the failed row rolls back with its migration. What survives is PostGIS
     * installed, a history table present, and zero rows.
     *
     * <p>No row is ever written by hand; the fixture is the defect reproducing itself.
     */
    private static String liveEquivalentDatabase(String name) throws Exception {
        String db = managedDatabase(name);
        Throwable failure = catchThrowable(() -> asMigrationRole(db, true).migrate());
        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains(AZURE_GUARD_MESSAGE);
        return db;
    }

    /** STATE B — pinned as its own case so the live shape is asserted, not merely constructed. */
    @Test
    void stateB_liveShape_isPostgisPresentPlusAnEmptyHistoryTable() throws Exception {
        String db = liveEquivalentDatabase("state_b");

        assertThat(tableExists(db, "spatial_ref_sys")).isTrue();
        assertThat(tableExists(db, "flyway_schema_history")).isTrue();
        assertThat(historyRows(db)).isEmpty();
        assertThat(tableNames(db))
                .as("no application table may exist in the live-equivalent state")
                .isEmpty();
        record("STATE B", "postgis=present historyTable=present rows=0 appTables=0");
    }

    /** STATE B — and re-running the shipped configuration on it changes nothing but the error. */
    @Test
    void stateB_emptyHistoryTable_baselineOnMigrateRemainsIneffective() throws Exception {
        String db = liveEquivalentDatabase("state_b_retry");

        Throwable failure = catchThrowable(() -> asMigrationRole(db, true).migrate());
        List<String> history = historyRows(db);
        record("STATE B/retry", outcome(failure) + " | baselineRow=" + hasBaseline(history));

        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains(AZURE_GUARD_MESSAGE);
        assertThat(hasBaseline(history)).isFalse();
        assertContractHolds("STATE B", history);
    }

    // ------------------------------------------- §5/§6 explicit baseline evidence

    /**
     * STATE D on a database that has never been migrated — no history table at all. Flyway's
     * supported {@code baseline()} operation, run by the unprivileged migration role, writes its own
     * BASELINE marker and {@code migrate()} then applies V2…V40. V1 is never executed.
     */
    @Test
    void stateD_explicitBaselineOnACleanManagedDatabase_skipsV1AndReachesHead() throws Exception {
        String db = managedDatabase("state_d_clean");
        assertThat(tableExists(db, "flyway_schema_history")).isFalse();

        Flyway flyway = asMigrationRole(db, false);
        flyway.baseline();
        assertThat(historyRows(db)).hasSize(1);
        assertThat(historyRows(db).get(0)).contains("|1|BASELINE|true");

        MigrateResult result = flyway.migrate();
        List<String> history = historyRows(db);
        record("STATE D/clean", "migrations=" + result.migrationsExecuted
                + " head=" + currentVersion(flyway));

        assertHeadReachedWithoutV1(flyway, result, history);
    }

    /**
     * §6, the mandatory case — the live shape, where a history table already exists and is empty.
     *
     * <p>Flyway's {@code baseline()} refuses it outright, and its own message names the remedy:
     * "Unable to baseline schema history table … as it already exists, and is empty. Delete the
     * schema history table, and run baseline again." So the live preparation cannot be
     * baseline-only; dropping the empty table first is not an improvisation but Flyway's documented
     * route, and this test is the evidence for the one-time mutation plan.
     */
    @Test
    void stateD_liveShape_requiresDroppingTheEmptyHistoryTableBeforeBaseline() throws Exception {
        String db = liveEquivalentDatabase("state_d_live");

        Throwable refusal = catchThrowable(() -> asMigrationRole(db, false).baseline());
        record("STATE D/live refusal", outcome(refusal));
        assertThat(refusal).isNotNull();
        assertThat(rootMessage(refusal))
                .contains("already exists, and is empty")
                .contains("Delete the schema history table");
        assertThat(tableExists(db, "flyway_schema_history"))
                .as("a refused baseline must not have mutated anything")
                .isTrue();
        assertThat(historyRows(db)).isEmpty();

        dropEmptyHistoryTableAsMigrationRole(db);

        Flyway flyway = asMigrationRole(db, false);
        flyway.baseline();
        assertThat(historyRows(db)).hasSize(1);
        assertThat(historyRows(db).get(0)).contains("|1|BASELINE|true");

        MigrateResult result = flyway.migrate();
        List<String> history = historyRows(db);
        record("STATE D/live", "migrations=" + result.migrationsExecuted
                + " head=" + currentVersion(flyway) + " rows=" + history.size());

        assertHeadReachedWithoutV1(flyway, result, history);
    }

    /** §5 — replaying the bootstrap baseline step must not corrupt or duplicate history. */
    @Test
    void explicitBaselineIsIdempotentAndReplaySafe() throws Exception {
        String db = liveEquivalentDatabase("state_idempotent");
        dropEmptyHistoryTableAsMigrationRole(db);

        Flyway flyway = asMigrationRole(db, false);
        flyway.baseline();
        flyway.baseline(); // replayed bootstrap, same version
        assertThat(historyRows(db)).hasSize(1);

        flyway.migrate();
        int converged = historyRows(db).size();

        flyway.baseline(); // replayed again after convergence
        flyway.migrate();

        List<String> history = historyRows(db);
        record("IDEMPOTENCY", "rows=" + history.size() + " (converged=" + converged + ")");
        assertThat(history).hasSize(converged);
        assertThat(currentVersion(flyway)).isEqualTo(String.valueOf(EXPECTED_HEAD));
        assertThat(history).noneMatch(row -> row.contains("|1|SQL|"));
    }

    /**
     * §12 fail-closed — baseline must refuse a database that has already been baselined at a
     * different version, so a mis-targeted bootstrap step cannot rewrite an established lineage.
     */
    @Test
    void explicitBaselineFailsClosedOnAnAlreadyBaselinedDatabase() throws Exception {
        String db = managedDatabase("state_ambiguous");
        Flyway flyway = asMigrationRole(db, false);
        flyway.baseline();
        flyway.migrate();
        int rowsBefore = historyRows(db).size();

        Flyway wrongBaseline = Flyway.configure()
                .dataSource(urlFor(db), MIGRATION_ROLE, MIGRATION_PASSWORD)
                .locations(LOCATIONS)
                .baselineVersion("30")
                .load();
        Throwable failure = catchThrowable(wrongBaseline::baseline);

        record("FAIL-CLOSED/baseline", outcome(failure));
        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains("has already been baselined");
        assertThat(historyRows(db)).hasSize(rowsBefore);
    }

    /**
     * §12 fail-closed — baseline must also refuse a database that carries real applied migrations,
     * so a mis-targeted preparation step cannot paper over recorded lineage.
     */
    @Test
    void explicitBaselineFailsClosedOnADatabaseWithAppliedMigrations() throws Exception {
        String db = "state_applied";
        recreateDatabase(db);
        Flyway owner = flyway(db, POSTGIS.getUsername(), POSTGIS.getPassword(), false);
        owner.migrate();
        List<String> before = historyRows(db);

        Throwable failure = catchThrowable(owner::baseline);

        record("FAIL-CLOSED/applied", outcome(failure));
        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains("already contains migrations");
        assertThat(historyRows(db)).isEqualTo(before);
    }

    // ------------------------------- §9 the shipped mechanism, exercised as shipped

    /**
     * The mechanism the managed profile actually runs — {@link ManagedFlywayBaselineStrategy} —
     * against a database in the state {@code bootstrap-invite-production-databases.sh} leaves
     * behind. This is the contract for any NEW managed parking database.
     */
    @Test
    void strategy_onACleanManagedDatabase_baselinesAndReachesHead() throws Exception {
        String db = managedDatabase("strategy_clean");
        Flyway flyway = asMigrationRole(db, false);

        new ManagedFlywayBaselineStrategy().migrate(flyway);

        List<String> history = historyRows(db);
        record("STRATEGY/clean", "head=" + currentVersion(flyway) + " rows=" + history.size());
        assertThat(currentVersion(flyway)).isEqualTo(String.valueOf(EXPECTED_HEAD));
        assertThat(history.get(0)).contains("|1|BASELINE|true");
        assertThat(history).noneMatch(row -> row.contains("|1|SQL|"));
        assertThat(history).hasSize(EXPECTED_HEAD);
    }

    /**
     * The live state. The strategy must refuse it rather than crash-loop on V1 or silently drop a
     * table, and the refusal must name the operator remedy.
     */
    @Test
    void strategy_onTheLiveEmptyHistoryTable_failsClosedAndNamesTheRemedy() throws Exception {
        String db = liveEquivalentDatabase("strategy_live");

        Throwable failure =
                catchThrowable(() -> new ManagedFlywayBaselineStrategy().migrate(asMigrationRole(db, false)));

        record("STRATEGY/live", outcome(failure));
        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure))
                .contains("empty flyway_schema_history table")
                .contains(ManagedFlywayBaselineStrategy.PREPARATION_SCRIPT);
        assertThat(historyRows(db))
                .as("a refusal must not mutate the database")
                .isEmpty();
        assertThat(tableExists(db, "flyway_schema_history")).isTrue();
        assertThat(tableNames(db)).isEmpty();
    }

    /**
     * The full live remediation as it will actually be sequenced: operator preparation drops the
     * empty history table, and the next deploy's strategy does the rest. Nothing else is touched.
     */
    @Test
    void strategy_afterOperatorPreparation_baselinesAndReachesHead() throws Exception {
        String db = liveEquivalentDatabase("strategy_prepared");
        dropEmptyHistoryTableAsMigrationRole(db);

        Flyway flyway = asMigrationRole(db, false);
        new ManagedFlywayBaselineStrategy().migrate(flyway);

        List<String> history = historyRows(db);
        record("STRATEGY/prepared", "head=" + currentVersion(flyway) + " rows=" + history.size());
        assertThat(currentVersion(flyway)).isEqualTo(String.valueOf(EXPECTED_HEAD));
        assertThat(history.get(0)).contains("|1|BASELINE|true");
        assertThat(history).noneMatch(row -> row.contains("|1|SQL|"));
        assertThat(history).allMatch(row -> row.endsWith("|true"));

        // Every subsequent boot must be a plain no-op migrate, not a second baseline.
        new ManagedFlywayBaselineStrategy().migrate(flyway);
        assertThat(historyRows(db)).isEqualTo(history);
    }

    /**
     * §9 — the strategy must never baseline an owner environment that legitimately applied V1.
     * Armed against such a database it migrates only, leaving the recorded V1 lineage intact.
     */
    @Test
    void strategy_onAnOwnerEnvironmentWithV1Applied_migratesWithoutBaselining() throws Exception {
        String db = "strategy_owner";
        recreateDatabase(db);
        Flyway owner = flyway(db, POSTGIS.getUsername(), POSTGIS.getPassword(), false);
        owner.migrate();
        List<String> before = historyRows(db);

        new ManagedFlywayBaselineStrategy().migrate(owner);

        record("STRATEGY/owner", "unchanged rows=" + historyRows(db).size());
        assertThat(historyRows(db)).isEqualTo(before);
        assertThat(historyRows(db).get(0)).contains("|1|SQL|true");
        assertThat(historyRows(db)).noneMatch(row -> row.contains("|BASELINE|"));
    }

    /**
     * §12 — a schema carrying application tables with no history table is ambiguous: baselining
     * would declare migrations applied that may never have run. The strategy must refuse.
     */
    @Test
    void strategy_onUnexplainedApplicationTables_failsClosed() throws Exception {
        String db = managedDatabase("strategy_unexplained");
        try (Connection c = DriverManager.getConnection(
                        urlFor(db), MIGRATION_ROLE, MIGRATION_PASSWORD);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE parking_spots_orphan (id bigint PRIMARY KEY)");
        }

        Throwable failure =
                catchThrowable(() -> new ManagedFlywayBaselineStrategy().migrate(asMigrationRole(db, false)));

        record("STRATEGY/unexplained", outcome(failure));
        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains("application table(s) but no");
        assertThat(tableExists(db, "flyway_schema_history")).isFalse();
    }

    /**
     * §12 — PostGIS absent means baselining past V1 would leave V2's geography column with nothing
     * to bind to. Refuse before writing a baseline marker that would be wrong.
     */
    @Test
    void strategy_withoutPostgis_failsClosedBeforeBaselining() throws Exception {
        String db = "strategy_no_postgis";
        recreateDatabase(db);
        applyBootstrapGrants(db);
        installAzureExtensionGuard(db);

        Throwable failure =
                catchThrowable(() -> new ManagedFlywayBaselineStrategy().migrate(asMigrationRole(db, false)));

        record("STRATEGY/no-postgis", outcome(failure));
        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains("no postgis extension");
        assertThat(tableExists(db, "flyway_schema_history")).isFalse();
    }

    // ------------------------------------------------- §8 owner environments (STATE E)

    /**
     * STATE E — hosted-beta and local run PostgreSQL where the connecting identity may install the
     * extension and no Azure hook exists. V1 must still execute as a normal SQL migration and
     * record its checksum, and the resulting history must still validate. This is the constraint
     * that forbids rewriting V1.
     */
    @Test
    void stateE_ownerEnvironment_appliesV1NormallyAndStillValidates() throws Exception {
        String db = "state_e";
        recreateDatabase(db);

        Flyway flyway = flyway(db, POSTGIS.getUsername(), POSTGIS.getPassword(), false);
        MigrateResult result = flyway.migrate();
        List<String> history = historyRows(db);
        record("STATE E", "target=" + currentVersion(flyway) + " first=" + history.get(0));

        assertThat(result.success).isTrue();
        assertThat(currentVersion(flyway)).isEqualTo(String.valueOf(EXPECTED_HEAD));
        assertThat(history.get(0))
                .as("the owner path must execute V1 as a real SQL migration, not a baseline")
                .contains("|1|SQL|true");
        assertThat(history).allMatch(row -> row.endsWith("|true"));

        // Cross-check §8: the number Flyway actually wrote for V1 is the number the Docker-free
        // immutability test pins. This is what makes that unit test a real guard rather than a
        // self-consistent restatement of its own algorithm.
        assertThat(recordedChecksum(db, "1"))
                .as("V1's recorded checksum must equal the pinned value")
                .isEqualTo(ParkingMigrationV1ImmutabilityTest.V1_FLYWAY_CHECKSUM);

        // Re-validating checks the recorded V1 checksum; a rewritten V1 would fail here, which is
        // exactly what a managed-profile "fix" that edited the file would do to hosted-beta.
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    /**
     * §9 — the managed and owner mechanisms must not leak into each other. A baselined managed
     * database and a V1-applied owner database converge on the same head version with the same
     * applied-migration count; only the version-1 row differs in kind.
     */
    @Test
    void managedAndOwnerPathsConvergeOnTheSameSchemaHead() throws Exception {
        String managed = managedDatabase("state_converge_managed");
        Flyway managedFlyway = asMigrationRole(managed, false);
        managedFlyway.baseline();
        managedFlyway.migrate();

        String owner = "state_converge_owner";
        recreateDatabase(owner);
        Flyway ownerFlyway = flyway(owner, POSTGIS.getUsername(), POSTGIS.getPassword(), false);
        ownerFlyway.migrate();

        assertThat(currentVersion(managedFlyway)).isEqualTo(currentVersion(ownerFlyway));
        assertThat(historyRows(managed)).hasSameSizeAs(historyRows(owner));
        assertThat(tableNames(managed))
                .as("both paths must produce the same application tables")
                .isEqualTo(tableNames(owner));
        record("CONVERGENCE", "head=" + currentVersion(managedFlyway)
                + " tables=" + tableNames(managed).size());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Drop the empty history table as the <em>migration</em> role, guarded by the same precondition
     * the live preparation tool enforces: the table exists and holds exactly zero rows. Proves the
     * drop needs no elevated identity — the migration role already owns the table it created.
     */
    private static void dropEmptyHistoryTableAsMigrationRole(String database) throws Exception {
        try (Connection c = DriverManager.getConnection(
                        urlFor(database), MIGRATION_ROLE, MIGRATION_PASSWORD);
                Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM flyway_schema_history")) {
                assertThat(rs.next() && rs.getInt(1) == 0)
                        .as("fail closed: only a provably empty history table may be dropped")
                        .isTrue();
            }
            s.execute("DROP TABLE flyway_schema_history");
        }
    }

    private static void assertHeadReachedWithoutV1(
            Flyway flyway, MigrateResult result, List<String> history) throws Exception {
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(EXPECTED_HEAD - 1);
        assertThat(currentVersion(flyway)).isEqualTo(String.valueOf(EXPECTED_HEAD));
        assertThat(history).noneMatch(row -> row.contains("|1|SQL|"));
        assertThat(history.get(0)).contains("|1|BASELINE|true");
        assertThat(history).anyMatch(row -> row.contains("|2|SQL|true"));
        assertThat(history).anyMatch(row -> row.contains("|" + EXPECTED_HEAD + "|SQL|true"));
        assertThat(history).allMatch(row -> row.endsWith("|true"));
        assertThat(history).hasSize(EXPECTED_HEAD);

        // A baselined database must still validate, so the runtime's own Flyway pass on the next
        // boot is a no-op rather than a startup failure.
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted)
                .as("a second migrate on a converged database applies nothing")
                .isZero();
    }

    private static Integer recordedChecksum(String database, String version) throws Exception {
        try (Connection c = DriverManager.getConnection(
                        urlFor(database), POSTGIS.getUsername(), POSTGIS.getPassword());
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT checksum FROM flyway_schema_history WHERE version = '"
                                + version + "'")) {
            return rs.next() ? rs.getInt(1) : null;
        }
    }

    private static List<String> tableNames(String database) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(
                        urlFor(database), POSTGIS.getUsername(), POSTGIS.getPassword());
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid ="
                                + " c.relnamespace LEFT JOIN pg_depend d ON d.objid = c.oid AND"
                                + " d.deptype = 'e' WHERE n.nspname = 'public' AND c.relkind = 'r'"
                                + " AND d.objid IS NULL AND c.relname <> 'flyway_schema_history'"
                                + " ORDER BY c.relname")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    /**
     * The one invariant every managed-profile state must satisfy regardless of which Flyway
     * behaviour it exhibits: V1 is never executed as a SQL migration under the application identity.
     */
    private static void assertContractHolds(String state, List<String> history) {
        assertThat(history)
                .as("%s must never record V1 as an executed SQL migration", state)
                .noneMatch(row -> row.contains("|1|SQL|"));
    }

    private static boolean hasBaseline(List<String> history) {
        return history.stream().anyMatch(row -> row.contains("|BASELINE|"));
    }

    private static String currentVersion(Flyway flyway) {
        var current = flyway.info().current();
        return current == null || current.getVersion() == null
                ? "NONE"
                : current.getVersion().getVersion();
    }

    private static String outcome(Throwable failure) {
        return failure == null ? "migrate() succeeded" : "migrate() threw: " + firstLine(failure);
    }

    /** Flyway nests the driver error; flatten the whole chain so assertions see the real cause. */
    private static String rootMessage(Throwable t) {
        StringBuilder all = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            all.append(cur.getMessage()).append('\n');
        }
        return all.toString();
    }

    private static String firstLine(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getMessage() == null) {
            root = root.getCause();
        }
        String message = String.valueOf(root.getMessage());
        int nl = message.indexOf('\n');
        return (nl < 0 ? message : message.substring(0, nl)).trim();
    }

    /** Single evidence channel so a CI log can be read as the R8.5 result table. */
    private static void record(String label, String detail) {
        System.out.println("[R8.5][" + label + "] " + detail);
    }

    private static Throwable catchThrowable(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
