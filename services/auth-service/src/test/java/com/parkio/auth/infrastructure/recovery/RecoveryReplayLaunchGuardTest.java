package com.parkio.auth.infrastructure.recovery;

import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.ATTEMPT;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.DATASET;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.EVIDENCE_IDENTITY;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.TARGET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.auth.infrastructure.recovery.RecoveryFixtures.Target;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PR #295 review N16 (Y3): a connection refused by layer 2 decides the exit (22) even when the
 * code that asked for it swallows the refusal, whether that happens while the command context
 * starts or while the command runs and concludes COMPLETE. The launch runs for real on a small
 * context whose only data source reaches another database. Synthetic ids and keys only.
 */
class RecoveryReplayLaunchGuardTest {

    private static final String ELSEWHERE = "postgresql:7000000000000000123:parkio_auth";
    private static final Map<String, String> PROPERTIES = Map.of(
            RecoveryReplayPreflight.FLAG, "true",
            "spring.datasource.url", RecoveryFixtures.DATASOURCE_URL,
            "spring.main.banner-mode", "off");

    private static final AtomicBoolean COMMAND_RAN = new AtomicBoolean();

    @TempDir Path dir;

    private Path verdict;
    private String[] args;

    @BeforeEach
    void inputs() {
        verdict = dir.resolve("verdict.json");
        args = RecoveryFixtures.args(RecoveryFixtures.trustedSet(dir, ATTEMPT, DATASET, TARGET),
                RecoveryFixtures.trustDocument(dir, EVIDENCE_IDENTITY), ATTEMPT, DATASET, TARGET, verdict);
        PROPERTIES.forEach(System::setProperty);
        COMMAND_RAN.set(false);
    }

    @AfterEach
    void clearProperties() {
        PROPERTIES.keySet().forEach(System::clearProperty);
    }

    @Test
    void aRefusalSwallowedWhileTheContextStartsEndsTheRunBeforeTheCommand() {
        int exit = launch(SwallowsAtStart.class);

        assertThat(exit).isEqualTo(RecoveryReplayExit.TARGET_REFUSED.code());
        assertThat(COMMAND_RAN.get()).as("no replay on a context that reached another database").isFalse();
        assertThat(RecoveryFixtures.readTree(verdict).path("reason").asText())
                .isEqualTo("a connection of the command context reached a database the preflight did not check");
    }

    @Test
    void aRefusalSwallowedDuringARunThatConcludedCompleteStillEndsTheRun() {
        int exit = launch(SwallowsDuringTheRun.class);

        assertThat(exit).isEqualTo(RecoveryReplayExit.TARGET_REFUSED.code());
        assertThat(RecoveryFixtures.readTree(verdict).path("status").asText()).isEqualTo("TARGET_REFUSED");
    }

    private int launch(Class<?> application) {
        return RecoveryReplayLaunch.run(application, args, Map.of("SPRING_PROFILES_ACTIVE", RecoveryReplayLaunch.PROFILE),
                new Properties(), RecoveryFixtures.preflight(Target.of(TARGET)));
    }

    /** A context whose bean asks for a connection at start and swallows the refusal. */
    @Configuration(proxyBeanMethods = false)
    static class SwallowsAtStart {

        @Bean
        DataSource dataSource() throws SQLException {
            return reaching(ELSEWHERE);
        }

        @Bean
        SmartInitializingSingleton swallowingStartup(DataSource dataSource) {
            return () -> swallow(dataSource);
        }

        @Bean
        RecoveryReplayCommand command() {
            RecoveryReplayCommand command = mock(RecoveryReplayCommand.class);
            when(command.execute(any(), any())).thenAnswer(invocation -> {
                COMMAND_RAN.set(true);
                return RecoveryReplayExit.COMPLETE;
            });
            return command;
        }
    }

    /** A context whose command touches another database, swallows the refusal and reports COMPLETE. */
    @Configuration(proxyBeanMethods = false)
    static class SwallowsDuringTheRun {

        @Bean
        DataSource dataSource() throws SQLException {
            return reaching(ELSEWHERE);
        }

        @Bean
        RecoveryReplayCommand command(DataSource dataSource) {
            RecoveryReplayCommand command = mock(RecoveryReplayCommand.class);
            when(command.execute(any(), any())).thenAnswer(invocation -> {
                swallow(dataSource);
                RecoveryReplayVerdict verdict = invocation.getArgument(1);
                RecoveryReplayPreflight.Plan plan = invocation.getArgument(0);
                return verdict.finish(RecoveryReplayExit.COMPLETE, plan.arguments().verdictOut());
            });
            return command;
        }
    }

    private static void swallow(DataSource dataSource) {
        try (Connection ignored = dataSource.getConnection()) {
            throw new AssertionError("the guard handed out a connection to another database");
        } catch (SQLException refusedAndSwallowed) {
            // the caller ignores the refusal; the launch must not
        }
    }

    private static DataSource reaching(String identity) throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(RecoveryReplayTarget.IDENTITY)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getString(1)).thenReturn(identity);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }
}
