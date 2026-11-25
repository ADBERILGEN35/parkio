package com.parkio.notification.infrastructure.persistence;

import com.parkio.notification.application.port.DeviceTokenHandover;
import com.parkio.notification.infrastructure.persistence.jpa.DeviceTokenJpaRepository;
import java.time.Duration;
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
 *
 * <p>From the token lock on, each lock wait of the registering transaction is bounded by
 * {@link #LOCK_TIMEOUT} (#601): the token lock, the row locks of the handover update and those of
 * the caller's own insert or update. A registration that cannot get one in time throws and rolls
 * back, so the previous holder keeps the token, and the client gets the generic 500
 * INTERNAL_ERROR of GlobalExceptionHandler's catch-all. The handover update finds its rows through
 * {@code idx_device_tokens_active_token} (V17).
 */
@Component
public class DeviceTokenHandoverAdapter implements DeviceTokenHandover {

    static final int LOCK_CLASS = 0x4E544B31; // "NTK1"; the owner fence uses "NTF1"

    /**
     * The longest one lock wait may take. The token lock's holders are other registrations of the
     * token (or of a token with the same hash). Rows can also be held by their owner deactivating
     * them, by the erasure handler, and by a push delivery tick, which writes a token it deactivated
     * after DeviceNotRegistered only when its batch commits, after its sends. All of them take
     * milliseconds and do no I/O while they hold the lock, so a longer wait means something is
     * wrong. 5 s is far above those waits and far below the gateway's 30 s response timeout, so the
     * caller gets an answer before the gateway gives up. It is transaction-local, so it ends with
     * the registration and never reaches a pooled connection; the datasource keeps no
     * lock_timeout, because its other transactions have other lock profiles.
     */
    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);

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
            jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class,
                    LOCK_TIMEOUT.toMillis() + "ms");
            jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", LOCK_CLASS, key(token));
        }
        return jpa.deactivateActiveForOtherUsers(token, userId, now);
    }

    static int key(String token) {
        return token.hashCode();
    }
}
