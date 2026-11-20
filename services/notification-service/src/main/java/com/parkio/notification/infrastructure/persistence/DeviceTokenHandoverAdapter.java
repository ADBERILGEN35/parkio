package com.parkio.notification.infrastructure.persistence;

import com.parkio.notification.application.port.DeviceTokenHandover;
import com.parkio.notification.infrastructure.persistence.jpa.DeviceTokenJpaRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Deactivates other accounts' rows for a push token under a transaction-scoped PostgreSQL advisory
 * lock keyed on the token. Without the lock, two accounts registering one token at the same time
 * would each miss the other's uncommitted row and both stay active. The H2 unit-test database has
 * no advisory locks and skips it.
 */
@Component
public class DeviceTokenHandoverAdapter implements DeviceTokenHandover {

    static final int LOCK_CLASS = 0x4E544B31; // "NTK1"; the owner fence uses "NTF1"

    private final DeviceTokenJpaRepository jpa;
    private final JdbcTemplate jdbc;
    private final boolean postgresql;

    public DeviceTokenHandoverAdapter(DeviceTokenJpaRepository jpa, JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.jdbc = jdbc;
        this.postgresql = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)
                connection -> "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }

    @Override
    public int deactivateForOtherUsers(String token, UUID userId, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "The device token handover is transaction-scoped; call it inside the registering transaction");
        }
        if (postgresql) {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", LOCK_CLASS, key(token));
        }
        return jpa.deactivateActiveForOtherUsers(token, userId, now);
    }

    static int key(String token) {
        return token.hashCode();
    }
}
