package com.parkio.media.infrastructure.persistence;

import com.parkio.media.application.port.MediaOwnerFence;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link MediaOwnerFence} on a PostgreSQL transaction-scoped advisory lock per owner: writes take
 * it shared, the erasure exclusively; PostgreSQL releases it at commit or rollback, so a crashed
 * process never leaves it held. The {@code (int, int)} key space is separate from single
 * {@code bigint} advisory keys; distinct owners whose keys collide only wait for each other.
 * The admission check reads the tombstone after the lock is held (READ COMMITTED), so it sees a
 * tombstone committed by an erasure that held the fence before it. On the H2 database of the
 * context tests there is no advisory lock; only the tombstone check runs.
 */
@Component
public class MediaOwnerFenceAdapter implements MediaOwnerFence {

    /** Advisory-lock class of the media owner fence ("MED1"). */
    static final int LOCK_CLASS = 0x4D454431;

    private final JdbcTemplate jdbc;
    private final boolean postgresql;

    public MediaOwnerFenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.postgresql = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)
                connection -> "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }

    @Override
    public boolean admitWrite(UUID ownerUserId) {
        requireTransaction();
        if (postgresql) {
            jdbc.queryForList("SELECT pg_advisory_xact_lock_shared(?, ?)", LOCK_CLASS, key(ownerUserId));
        }
        Boolean erased = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM erased_user_tombstones WHERE auth_user_id = ?)", Boolean.class,
                ownerUserId);
        return !Boolean.TRUE.equals(erased);
    }

    @Override
    public void holdForErasure(UUID ownerUserId) {
        requireTransaction();
        if (postgresql) {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", LOCK_CLASS, key(ownerUserId));
        }
    }

    static int key(UUID ownerUserId) {
        return ownerUserId.hashCode();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The media owner fence is transaction-scoped; call it inside a transaction");
        }
    }
}
