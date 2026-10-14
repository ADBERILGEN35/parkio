package com.parkio.moderation.infrastructure.persistence;

import com.parkio.moderation.application.port.ModerationOwnerFence;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL advisory lock + tombstone admit; H2 tombstone-only. */
@Component
public class ModerationOwnerFenceAdapter implements ModerationOwnerFence {

    static final int LOCK_CLASS = 0x4D4F4431; // "MOD1"

    private final JdbcTemplate jdbc;
    private final boolean postgresql;

    public ModerationOwnerFenceAdapter(JdbcTemplate jdbc) {
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
                    "The moderation owner fence is transaction-scoped; call it inside a transaction");
        }
    }
}
