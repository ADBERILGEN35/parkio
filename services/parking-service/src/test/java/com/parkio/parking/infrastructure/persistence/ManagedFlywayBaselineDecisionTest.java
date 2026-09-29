package com.parkio.parking.infrastructure.persistence;

import static com.parkio.parking.infrastructure.persistence.ManagedFlywayBaselineStrategy.Decision;
import static com.parkio.parking.infrastructure.persistence.ManagedFlywayBaselineStrategy.SchemaState;
import static com.parkio.parking.infrastructure.persistence.ManagedFlywayBaselineStrategy.decide;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * PROD-DEPLOY-01A-R8.5 — the managed-baseline decision table, Docker-free.
 *
 * <p>{@code ManagedParkingFlywayBaselineIT} proves the mechanism against a real PostGIS; this pins
 * the branch each observed schema state must take, including every state that must fail closed
 * rather than baseline. Kept as a pure-function test so the whole matrix runs in Backend CI without
 * a container.
 */
class ManagedFlywayBaselineDecisionTest {

    private static SchemaState state(
            boolean historyTable, long rows, long failed, long appTables, boolean postgis) {
        return new SchemaState(historyTable, rows, failed, appTables, postgis);
    }

    @Test
    void preBaselineManagedDatabaseIsBaselinedThenMigrated() {
        // The state bootstrap-invite-production-databases.sh leaves behind: PostGIS provisioned by
        // the administrator, nothing else.
        assertThat(decide(state(false, 0, 0, 0, true)))
                .isEqualTo(Decision.BASELINE_THEN_MIGRATE);
    }

    @Test
    void establishedHistoryMigratesWithoutBaselining() {
        assertThat(decide(state(true, 40, 0, 44, true))).isEqualTo(Decision.MIGRATE_ONLY);
    }

    @Test
    void aSingleBaselineRowIsAlreadyEstablishedHistory() {
        // Straight after the first managed deploy converges the baseline exists but V2+ have not
        // been applied yet in a crash/restart window; migrating is correct, re-baselining is not.
        assertThat(decide(state(true, 1, 0, 0, true))).isEqualTo(Decision.MIGRATE_ONLY);
    }

    @Test
    void emptyHistoryTableFailsClosed() {
        // The live invite-production state. Flyway cannot baseline over it and this service will
        // not drop a table at startup.
        SchemaState live = state(true, 0, 0, 0, true);
        assertThat(decide(live)).isEqualTo(Decision.REFUSE_EMPTY_HISTORY_TABLE);
        assertThat(ManagedFlywayBaselineStrategy.refusalMessage(decide(live), live))
                .contains(ManagedFlywayBaselineStrategy.PREPARATION_SCRIPT);
    }

    @Test
    void applicationTablesWithoutHistoryFailClosed() {
        // Baselining here would declare V2..Vn applied without knowing whether they were.
        SchemaState orphaned = state(false, 0, 0, 12, true);
        assertThat(decide(orphaned)).isEqualTo(Decision.REFUSE_UNEXPLAINED_TABLES);
        assertThat(ManagedFlywayBaselineStrategy.refusalMessage(decide(orphaned), orphaned))
                .contains("12 application table(s)");
    }

    @Test
    void missingPostgisFailsClosedRatherThanBaseliningPastV1() {
        // Skipping V1 is only sound because infrastructure already installed the extension.
        assertThat(decide(state(false, 0, 0, 0, false)))
                .isEqualTo(Decision.REFUSE_POSTGIS_MISSING);
    }

    @Test
    void aFailedMigrationRowFailsClosed() {
        assertThat(decide(state(true, 7, 1, 5, true))).isEqualTo(Decision.REFUSE_FAILED_MIGRATION);
    }

    @Test
    void unexplainedTablesOutrankAMissingExtension() {
        // Both are wrong, but "there is schema we cannot account for" is the more dangerous one and
        // must be the reported cause.
        assertThat(decide(state(false, 0, 0, 3, false)))
                .isEqualTo(Decision.REFUSE_UNEXPLAINED_TABLES);
    }

    @Test
    void refusalMessageIsNotAvailableForProceedingDecisions() {
        assertThatThrownBy(() -> ManagedFlywayBaselineStrategy.refusalMessage(
                        Decision.MIGRATE_ONLY, state(true, 40, 0, 44, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifierInterpolationRejectsAnythingButAPlainIdentifier() {
        assertThat(ManagedFlywayBaselineStrategy.quoteIdentifier("flyway_schema_history"))
                .isEqualTo("\"flyway_schema_history\"");
        assertThatThrownBy(() -> ManagedFlywayBaselineStrategy.quoteIdentifier("public\"; DROP"))
                .hasMessageContaining("unsafe SQL identifier");
        assertThatThrownBy(() -> ManagedFlywayBaselineStrategy.quoteIdentifier(null))
                .hasMessageContaining("unsafe SQL identifier");
    }
}
