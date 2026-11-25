package com.parkio.user.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The PostgreSQL behaviour itself is covered by {@code UserErasureFencePostgresIT}. */
class UserOwnerFenceAdapterTest {

    private static final UUID USER = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000071");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @BeforeEach
    void inTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void lockTimeoutIsAtLeastOneMillisecondAndFitsPostgresql() {
        assertThat(UserOwnerFenceAdapter.lockTimeoutSetting(Duration.ofSeconds(2))).isEqualTo("2000ms");
        assertThat(UserOwnerFenceAdapter.lockTimeoutSetting(Duration.ofMillis(1))).isEqualTo("1ms");
        for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofNanos(999_999), Duration.ofSeconds(-1),
                Duration.ofMillis(Integer.MAX_VALUE + 1L)}) {
            assertThatThrownBy(() -> UserOwnerFenceAdapter.lockTimeoutSetting(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parkio.privacy.erasure-fence.lock-timeout");
        }
    }

    @Test
    void waitThatReachesTheLockTimeoutFailsWithProfileBusy() {
        UserOwnerFenceAdapter fence = postgresqlFence();
        when(jdbc.queryForList(startsWith("SELECT pg_advisory_xact_lock_shared"),
                eq(UserOwnerFenceAdapter.LOCK_CLASS), anyInt(), eq("2000ms")))
                .thenThrow(new CannotAcquireLockException("lock timeout",
                        new SQLException("canceling statement due to lock timeout", "55P03")));

        assertThatThrownBy(() -> fence.admitWrite(USER)).isInstanceOfSatisfying(UserException.class,
                e -> assertThat(e.errorCode()).isEqualTo(UserErrorCode.PROFILE_BUSY));
    }

    @Test
    void otherLockStatementFailuresAreNotReportedAsBusy() {
        UserOwnerFenceAdapter fence = postgresqlFence();
        var failure = new DataAccessResourceFailureException("connection lost",
                new SQLException("terminating connection", "57P01"));
        when(jdbc.queryForList(startsWith("SELECT pg_advisory_xact_lock_shared"),
                eq(UserOwnerFenceAdapter.LOCK_CLASS), anyInt(), anyString()))
                .thenThrow(failure);

        assertThatThrownBy(() -> fence.admitWrite(USER)).isSameAs(failure);
    }

    @SuppressWarnings("unchecked")
    private UserOwnerFenceAdapter postgresqlFence() {
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(Boolean.TRUE);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("0");
        return new UserOwnerFenceAdapter(jdbc, Duration.ofSeconds(2));
    }
}
