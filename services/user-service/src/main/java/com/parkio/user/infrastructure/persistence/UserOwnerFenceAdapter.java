package com.parkio.user.infrastructure.persistence;

import com.parkio.user.application.port.UserOwnerFence;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class UserOwnerFenceAdapter implements UserOwnerFence {

    static final int LOCK_CLASS = 0x55534631; // "USF1"
    /** PostgreSQL {@code lock_not_available}: the wait ended at {@code lock_timeout}. */
    static final String LOCK_NOT_AVAILABLE = "55P03";

    private static final Logger log = LoggerFactory.getLogger(UserOwnerFenceAdapter.class);

    private final JdbcTemplate jdbc;
    private final boolean postgresql;
    private final String lockTimeout;

    public UserOwnerFenceAdapter(JdbcTemplate jdbc,
                                 @Value("${parkio.privacy.erasure-fence.lock-timeout:PT2S}") Duration lockTimeout) {
        this.jdbc = jdbc;
        this.lockTimeout = lockTimeoutSetting(lockTimeout);
        this.postgresql = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)
                connection -> "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }

    /**
     * On PostgreSQL the wait for the shared lock is bounded by {@code lock_timeout} (#597). A
     * stuck erasure then fails its waiters with {@code PROFILE_BUSY} instead of holding their
     * transactions and pooled connections until it ends. Only this wait is bounded: the
     * transaction's previous {@code lock_timeout} is restored once the lock is held.
     */
    @Override
    public boolean admitWrite(UUID userId) {
        requireTransaction();
        if (!postgresql) {
            return !Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM erased_user_tombstones WHERE auth_user_id = ?)",
                    Boolean.class,
                    userId));
        }
        String previous = jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
        try {
            // The subquery sets the bound before the lock is requested; OFFSET 0 keeps it a subquery.
            jdbc.queryForList("SELECT pg_advisory_xact_lock_shared(?, ?)"
                            + " FROM (SELECT set_config('lock_timeout', ?, true) OFFSET 0) AS bounded",
                    LOCK_CLASS, key(userId), lockTimeout);
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql
                    && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                log.warn("Erasure fence wait for user {} exceeded {}; an account erasure may be stuck",
                        userId, lockTimeout);
                throw new UserException(UserErrorCode.PROFILE_BUSY);
            }
            throw e;
        }
        // Restoring the timeout and reading the tombstone do not depend on each other.
        Boolean erased = jdbc.queryForObject(
                "SELECT set_config('lock_timeout', ?, true) AS restored,"
                        + " EXISTS (SELECT 1 FROM erased_user_tombstones WHERE auth_user_id = ?) AS erased",
                (rs, row) -> rs.getBoolean("erased"),
                previous,
                userId);
        return !Boolean.TRUE.equals(erased);
    }

    @Override
    public void holdForErasure(UUID userId) {
        requireTransaction();
        if (postgresql) {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", LOCK_CLASS, key(userId));
        }
    }

    static int key(UUID userId) {
        return userId.hashCode();
    }

    /** At least 1 ms (PostgreSQL reads 0 as "no timeout") and within its integer range. */
    static String lockTimeoutSetting(Duration lockTimeout) {
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        long millis = lockTimeout.toMillis();
        if (millis < 1 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "parkio.privacy.erasure-fence.lock-timeout must be between 1ms and " + Integer.MAX_VALUE + "ms");
        }
        return millis + "ms";
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "The user owner fence is transaction-scoped; call it inside a transaction");
        }
    }
}
