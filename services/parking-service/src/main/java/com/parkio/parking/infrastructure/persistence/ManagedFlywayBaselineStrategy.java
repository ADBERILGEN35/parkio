package com.parkio.parking.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

/**
 * PROD-DEPLOY-01A-R8.5 — establishes the Flyway baseline explicitly on managed PostgreSQL, where
 * the migration role may not create the PostGIS extension.
 *
 * <h2>The constraint</h2>
 *
 * <p>Azure Database for PostgreSQL Flexible Server rejects {@code CREATE EXTENSION} for any role
 * outside {@code azure_pg_admin}, from a utility hook that fires <em>before</em> PostgreSQL's own
 * {@code IF NOT EXISTS} short-circuit. {@code V1__enable_postgis.sql} therefore cannot execute as
 * the application identity, even though the extension is already installed. Infrastructure
 * provisions PostGIS; the migration chain has to begin at V2.
 *
 * <h2>Why not {@code baselineOnMigrate}</h2>
 *
 * <p>The previously shipped {@code SPRING_FLYWAY_BASELINE_ON_MIGRATE=true} does not do this job,
 * and {@code ManagedParkingFlywayBaselineIT} proves it against a real PostGIS with a real
 * unprivileged role. Flyway baselines on migrate only for a <em>non-empty</em> schema with no
 * history table, and its emptiness check excludes extension-owned objects — so a managed
 * {@code public} holding nothing but PostGIS reads as empty, the flag never engages, and V1 is
 * attempted exactly as if it were absent. Worse, the attempt leaves the history table behind with
 * its failed row rolled back, and from then on the table's mere existence suppresses baselining
 * for good. That is how live invite-production reached an empty history table it cannot migrate
 * out of.
 *
 * <h2>What this does instead</h2>
 *
 * <p>Calls Flyway's supported {@link Flyway#baseline()} when — and only when — the database is
 * provably in the pre-baseline managed state, then migrates normally. Every other state either
 * migrates untouched or fails closed with a message naming the remedy; nothing is ever written into
 * {@code flyway_schema_history} by hand.
 *
 * <p>The bean exists only when {@code parkio.parking.flyway.managed-baseline-enabled} is true, which
 * only the managed-DB compose profile sets. Local and hosted-beta run the unchanged path, where V1
 * executes normally and its checksum stays valid — which is why V1 is frozen rather than rewritten
 * (see {@code ParkingMigrationV1ImmutabilityTest}).
 */
public class ManagedFlywayBaselineStrategy implements FlywayMigrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ManagedFlywayBaselineStrategy.class);

    /** Named in operator-facing failure messages so the remedy is never guesswork. */
    static final String PREPARATION_SCRIPT =
            "scripts/azure/prepare-managed-parking-flyway-baseline.sh";

    /** What the observed schema state permits. */
    public enum Decision {
        /** Pre-baseline managed database: write the BASELINE marker, then apply V2+. */
        BASELINE_THEN_MIGRATE,
        /** Established lineage: migrate normally, baseline nothing. */
        MIGRATE_ONLY,
        /** History table present but empty — Flyway cannot baseline over it. Operator action. */
        REFUSE_EMPTY_HISTORY_TABLE,
        /** Application tables with no history table: real lineage would be silently skipped. */
        REFUSE_UNEXPLAINED_TABLES,
        /** Nothing to baseline onto: V2's geography column would fail immediately. */
        REFUSE_POSTGIS_MISSING,
        /** A previous migration is recorded as failed; repairing it is an operator decision. */
        REFUSE_FAILED_MIGRATION,
    }

    /** Everything the decision depends on, so the decision itself stays a pure function. */
    public record SchemaState(
            boolean historyTableExists,
            long historyRowCount,
            long failedMigrationCount,
            long applicationTableCount,
            boolean postgisInstalled) {}

    /**
     * The whole managed-profile contract, as a total function over the observed state. Kept free of
     * JDBC so every branch is covered by Docker-free unit tests.
     */
    public static Decision decide(SchemaState state) {
        if (state.historyTableExists()) {
            if (state.historyRowCount() == 0) {
                // Flyway refuses to baseline over an existing empty history table — its own message
                // is "already exists, and is empty. Delete the schema history table, and run
                // baseline again". Dropping a table is an operator mutation, never a startup side
                // effect, so this fails closed and points at the preparation script.
                return Decision.REFUSE_EMPTY_HISTORY_TABLE;
            }
            if (state.failedMigrationCount() > 0) {
                return Decision.REFUSE_FAILED_MIGRATION;
            }
            return Decision.MIGRATE_ONLY;
        }
        if (state.applicationTableCount() > 0) {
            // No history table, yet application tables exist. Baselining here would declare V2..Vn
            // applied without knowing whether they were. Refuse rather than guess.
            return Decision.REFUSE_UNEXPLAINED_TABLES;
        }
        if (!state.postgisInstalled()) {
            // Baselining past V1 is only sound because infrastructure already provisioned PostGIS.
            return Decision.REFUSE_POSTGIS_MISSING;
        }
        return Decision.BASELINE_THEN_MIGRATE;
    }

    static String refusalMessage(Decision decision, SchemaState state) {
        return switch (decision) {
            case REFUSE_EMPTY_HISTORY_TABLE -> "Managed parking database has an empty "
                    + "flyway_schema_history table. Flyway cannot baseline over it, and this "
                    + "service will not drop a table at startup. Run " + PREPARATION_SCRIPT
                    + " (read-only by default) and follow its instructions.";
            case REFUSE_UNEXPLAINED_TABLES -> "Managed parking database has "
                    + state.applicationTableCount() + " application table(s) but no "
                    + "flyway_schema_history table. Baselining would discard real migration "
                    + "lineage. Refusing; investigate before deploying.";
            case REFUSE_POSTGIS_MISSING -> "Managed parking database has no postgis extension. "
                    + "Infrastructure must provision it (bootstrap-invite-production-databases.sh) "
                    + "before the migration chain can begin at V2.";
            case REFUSE_FAILED_MIGRATION -> "Managed parking database records "
                    + state.failedMigrationCount() + " failed migration(s). Refusing to migrate or "
                    + "baseline over a failed state; operator repair required.";
            case BASELINE_THEN_MIGRATE, MIGRATE_ONLY -> throw new IllegalArgumentException(
                    "not a refusal: " + decision);
        };
    }

    @Override
    public void migrate(Flyway flyway) {
        SchemaState state = inspect(flyway);
        Decision decision = decide(state);
        log.info("Managed parking Flyway baseline check: state={} decision={}", state, decision);

        switch (decision) {
            case BASELINE_THEN_MIGRATE -> {
                log.info("Establishing Flyway baseline at version {} — PostGIS is provisioned by "
                                + "infrastructure and V1 cannot execute as the migration role.",
                        flyway.getConfiguration().getBaselineVersion());
                flyway.baseline();
                flyway.migrate();
            }
            case MIGRATE_ONLY -> flyway.migrate();
            default -> throw new FlywayException(refusalMessage(decision, state));
        }
    }

    // ------------------------------------------------------------------ inspection

    private SchemaState inspect(Flyway flyway) {
        String table = flyway.getConfiguration().getTable();
        DataSource dataSource = flyway.getConfiguration().getDataSource();
        try (Connection connection = dataSource.getConnection()) {
            String schema = resolveSchema(flyway, connection);
            boolean historyTableExists = relationExists(connection, schema, table);
            long rows = 0;
            long failed = 0;
            if (historyTableExists) {
                rows = countHistory(connection, schema, table, null);
                failed = countHistory(connection, schema, table, Boolean.FALSE);
            }
            return new SchemaState(
                    historyTableExists,
                    rows,
                    failed,
                    countApplicationTables(connection, schema, table),
                    postgisInstalled(connection));
        } catch (SQLException ex) {
            throw new FlywayException(
                    "Unable to inspect managed parking schema before migrating: " + ex.getMessage(),
                    ex);
        }
    }

    private static String resolveSchema(Flyway flyway, Connection connection) throws SQLException {
        String configured = flyway.getConfiguration().getDefaultSchema();
        if (configured == null) {
            String[] schemas = flyway.getConfiguration().getSchemas();
            configured = schemas != null && schemas.length > 0 ? schemas[0] : null;
        }
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT current_schema()")) {
            return rs.next() && rs.getString(1) != null ? rs.getString(1) : "public";
        }
    }

    private static boolean relationExists(Connection connection, String schema, String table)
            throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n"
                + " ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?"
                + " AND c.relkind IN ('r', 'p'))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** {@code success} null counts every row; {@code FALSE} counts failed migrations. */
    private static long countHistory(
            Connection connection, String schema, String table, Boolean success)
            throws SQLException {
        // Identifiers cannot be bound as parameters; both come from Flyway's own configuration and
        // are validated before interpolation so a hostile value cannot reach the statement.
        String sql = "SELECT count(*) FROM " + quoteIdentifier(schema) + "."
                + quoteIdentifier(table) + (success == null ? "" : " WHERE success = ?");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (success != null) {
                statement.setBoolean(1, success);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /**
     * Tables that represent application schema: ordinary and partitioned relations in the Flyway
     * schema, excluding extension-owned objects (PostGIS installs {@code spatial_ref_sys} into
     * {@code public}) and the history table itself.
     */
    private static long countApplicationTables(Connection connection, String schema, String table)
            throws SQLException {
        String sql = "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " LEFT JOIN pg_depend d ON d.objid = c.oid AND d.deptype = 'e'"
                + " WHERE n.nspname = ? AND c.relkind IN ('r', 'p') AND d.objid IS NULL"
                + " AND c.relname <> ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static boolean postgisInstalled(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'postgis')")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    static String quoteIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            throw new FlywayException("Refusing to interpolate unsafe SQL identifier: " + identifier);
        }
        return "\"" + identifier + "\"";
    }
}
