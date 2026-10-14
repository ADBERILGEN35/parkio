package com.parkio.gamification.infrastructure.persistence;

import com.parkio.gamification.application.port.GamificationOwnerFence;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link GamificationOwnerFence} on a PostgreSQL transaction-scoped advisory lock per user:
 * writes take it shared, erasure exclusively. On H2 (unit/context tests) only the tombstone
 * check runs.
 */
@Component
public class GamificationOwnerFenceAdapter implements GamificationOwnerFence {

    /** Advisory-lock class of the gamification owner fence ("GAM1"). */
    static final int LOCK_CLASS = 0x47414D31;

    private final JdbcTemplate jdbc;
    private final boolean postgresql;

    public GamificationOwnerFenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.postgresql = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)
                connection -> "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }

    @Override
    public boolean admitWrite(UUID userId) {
        requireTransaction();
        if (postgresql) {
            jdbc.queryForList("SELECT pg_advisory_xact_lock_shared(?, ?)", LOCK_CLASS, key(userId));
        }
        Boolean erased = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM erased_user_tombstones WHERE auth_user_id = ?)",
                Boolean.class,
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

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "The gamification owner fence is transaction-scoped; call it inside a transaction");
        }
    }
}
