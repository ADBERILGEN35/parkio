package com.parkio.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Guards the delivery-attempt schema contract: the backoff column and the partial
 * pending index the worker's polling query relies on must ship as a Flyway migration.
 * (Migrations run only against PostgreSQL; tests use H2 with generated schema, so the
 * SQL itself is asserted here rather than executed.)
 */
class DeliveryAttemptMigrationTest {

    private static final String MIGRATION = "/db/migration/V10__add_delivery_attempt_backoff.sql";

    @Test
    void backoffMigrationAddsNextAttemptAtAndPartialPendingIndex() throws IOException {
        String sql = readMigration();

        assertThat(sql).contains("ADD COLUMN next_attempt_at TIMESTAMPTZ");
        // Partial index restricted to the worker's polling predicate.
        assertThat(sql).contains("CREATE INDEX idx_nda_pending_next_attempt");
        assertThat(sql).contains("ON notification_delivery_attempts (next_attempt_at)");
        assertThat(sql).contains("WHERE status = 'PENDING'");
        // The superseded broad status index is removed.
        assertThat(sql).contains("DROP INDEX idx_nda_status_created");
    }

    @Test
    void backoffMigrationBackfillsExistingPendingRows() throws IOException {
        String sql = readMigration();

        assertThat(sql).contains("UPDATE notification_delivery_attempts");
        assertThat(sql).contains("SET next_attempt_at = created_at");
    }

    private static String readMigration() throws IOException {
        try (InputStream in = DeliveryAttemptMigrationTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
