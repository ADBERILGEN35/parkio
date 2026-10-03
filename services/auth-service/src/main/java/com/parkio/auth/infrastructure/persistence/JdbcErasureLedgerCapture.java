package com.parkio.auth.infrastructure.persistence;

import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.port.CapturedErasureLedger;
import com.parkio.auth.application.port.ErasureLedgerCapture;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lock-protocol capture of {@code erased_user_tombstones} for checkpoints (contract stage 2).
 * It reuses only the output of the #104 locked-snapshot SQL, not its code: one READ COMMITTED
 * transaction sets a bounded {@code lock_timeout} and {@code statement_timeout}, takes a SHARE
 * lock on the table (which waits for in-flight INSERTs and blocks new ones until commit), reads
 * the database clock and every row ordered by {@code auth_user_id}, and commits. A timeout or
 * any other error rolls the transaction back and propagates. PostgreSQL only.
 */
public final class JdbcErasureLedgerCapture implements ErasureLedgerCapture {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Duration lockTimeout;
    private final Duration statementTimeout;

    public JdbcErasureLedgerCapture(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                    Duration lockTimeout, Duration statementTimeout) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction = template;
        this.lockTimeout = requirePositive(lockTimeout, "lockTimeout");
        this.statementTimeout = requirePositive(statementTimeout, "statementTimeout");
    }

    @Override
    public CapturedErasureLedger capture() {
        return transaction.execute(status -> {
            String isolation = jdbc.queryForObject("SELECT current_setting('transaction_isolation')", String.class);
            if (!"read committed".equals(isolation)) {
                throw new IllegalStateException("tombstone capture needs READ COMMITTED, not " + isolation);
            }
            jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class, millis(lockTimeout));
            jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class,
                    millis(statementTimeout));
            jdbc.execute("LOCK TABLE erased_user_tombstones IN SHARE MODE");
            Instant coveredThrough = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
            List<ErasureLedgerEntry> entries = jdbc.query(
                    "SELECT auth_user_id, erased_at FROM erased_user_tombstones ORDER BY auth_user_id",
                    (rs, row) -> new ErasureLedgerEntry(rs.getObject("auth_user_id", UUID.class),
                            rs.getObject("erased_at", OffsetDateTime.class).toInstant()));
            return new CapturedErasureLedger(entries, coveredThrough);
        });
    }

    private static String millis(Duration duration) {
        return duration.toMillis() + "ms";
    }

    /** At least one millisecond: PostgreSQL reads a zero timeout as "no timeout". */
    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least 1ms");
        }
        return value;
    }
}
