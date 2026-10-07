package com.parkio.auth.infrastructure.recovery;

import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.TARGET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;

/**
 * Layer 2 of PR #295 review B6: in the command context every connection a data source hands out,
 * and the connection Flyway actually migrates through, must reach the database the preflight
 * checked. Anything else is refused (22) and closed before the caller can use it.
 */
class RecoveryReplayConnectionGuardTest {

    private static final String ELSEWHERE = "postgresql:7000000000000000001:parkio_auth";
    private final RecoveryReplayConnectionGuard guard = new RecoveryReplayConnectionGuard(TARGET);

    @Test
    void aConnectionToTheCheckedDatabaseIsHandedOut() throws Exception {
        Connection connection = connectionTo(TARGET);
        DataSource guarded = (DataSource) guard.dataSources().postProcessAfterInitialization(dataSource(connection), "ds");

        assertThat(guarded.getConnection()).isSameAs(connection);
        verify(connection, never()).close();
        assertThat(guard.refusal()).isNull();
    }

    @Test
    void aConnectionToAnyOtherDatabaseIsRefusedAndClosed() throws Exception {
        Connection connection = connectionTo(ELSEWHERE);
        DataSource guarded = (DataSource) guard.dataSources().postProcessAfterInitialization(dataSource(connection), "ds");

        assertThatThrownBy(guarded::getConnection).isInstanceOf(SQLException.class);
        verify(connection).close();
        assertThat(guard.refusal().exit()).isEqualTo(RecoveryReplayExit.TARGET_REFUSED);
        assertThat(guard.refusal().getMessage())
                .isEqualTo("a connection of the command context reached a database the preflight did not check");
    }

    @Test
    void aConnectionThatCannotBeIdentifiedIsRefused() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("permission denied"));
        DataSource guarded = (DataSource) guard.dataSources().postProcessAfterInitialization(dataSource(connection), "ds");

        assertThatThrownBy(guarded::getConnection).isInstanceOf(SQLException.class);
        verify(connection).close();
        assertThat(guard.refusal().getMessage()).isEqualTo("a connection of the command context could not be identified");
    }

    @Test
    void otherBeansAreLeftAlone() {
        Object bean = new Object();

        assertThat(guard.dataSources().postProcessAfterInitialization(bean, "other")).isSameAs(bean);
    }

    @Test
    void flywayMigratesOnlyThroughTheCheckedDatabase() throws Exception {
        Flyway checked = flywayOn(connectionTo(TARGET));
        guard.flyway().migrate(checked);
        verify(checked).migrate();

        Flyway redirected = flywayOn(connectionTo(ELSEWHERE));
        assertThatThrownBy(() -> guard.flyway().migrate(redirected)).isInstanceOf(IllegalStateException.class);
        verify(redirected, never()).migrate();
        assertThat(guard.refusal().exit()).isEqualTo(RecoveryReplayExit.TARGET_REFUSED);
    }

    private static Flyway flywayOn(Connection connection) throws SQLException {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        DataSource dataSource = dataSource(connection);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(flyway.getConfiguration()).thenReturn(configuration);
        return flyway;
    }

    private static DataSource dataSource(Connection connection) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }

    private static Connection connectionTo(String identity) throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(RecoveryReplayTarget.IDENTITY)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getString(1)).thenReturn(identity);
        return connection;
    }
}
