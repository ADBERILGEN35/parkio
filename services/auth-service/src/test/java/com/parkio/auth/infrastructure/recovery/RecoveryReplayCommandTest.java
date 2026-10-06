package com.parkio.auth.infrastructure.recovery;

import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.ATTEMPT;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.DATASET;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.EVIDENCE_IDENTITY;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.TARGET;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.USERS;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.args;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.application.ErasureRestoreReplayService;
import com.parkio.auth.application.RestoreReplayVerdict;
import com.parkio.auth.application.port.ErasureRestoreRepository;
import com.parkio.auth.application.port.ErasureRestoreRepository.RestoreAttempt;
import com.parkio.auth.infrastructure.recovery.RecoveryFixtures.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;

/**
 * The part of the recovery replay that runs in the command context, after the preflight accepted
 * the run: the identity re-check, attempt reuse, the bounded wait and the verdict. The real
 * PostgreSQL paths are in {@link RecoveryReplayCommandPostgresIT}, the real launch in
 * {@link RecoveryReplayLaunchPostgresIT}. Synthetic ids and keys only.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RecoveryReplayCommandTest {

    private static final Pattern FORBIDDEN_CLAIMS = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T|no later|absen|cutoff|complete coverage|all erasures|until now",
            Pattern.CASE_INSENSITIVE);

    @TempDir Path dir;

    private final ErasureRestoreReplayService replay = mock(ErasureRestoreReplayService.class);
    private final ErasureRestoreRepository restores = mock(ErasureRestoreRepository.class);
    private MockEnvironment environment;
    private Path evidence;
    private Path trust;
    private Path verdictFile;

    @BeforeEach
    void inputs() {
        environment = RecoveryFixtures.environment();
        evidence = RecoveryFixtures.trustedSet(dir, ATTEMPT, DATASET, TARGET);
        trust = RecoveryFixtures.trustDocument(dir, EVIDENCE_IDENTITY);
        verdictFile = dir.resolve("verdict.json");
        when(restores.findAttempt(any())).thenReturn(Optional.empty());
    }

    @Test
    void theCommandExistsOnlyUnderTheRecoveryReplayProfile() {
        assertThat(RecoveryReplayCommand.class.getAnnotation(Profile.class).value())
                .containsExactly(RecoveryReplayLaunch.PROFILE);
    }

    @Test
    void aContextConnectedElsewhereThanThePreflightCheckedIsRefused() {
        for (Supplier<String> elsewhere : List.<Supplier<String>>of(
                () -> "postgresql:7000000000000000123:parkio_auth",
                () -> null,
                () -> {
                    throw new IllegalStateException("connection refused");
                })) {
            RecoveryReplayExit exit = command(elsewhere).execute(plan(validArgs()), verdict());

            assertThat(exit).isEqualTo(RecoveryReplayExit.TARGET_REFUSED);
            assertThat(written().path("reason").asText())
                    .isEqualTo("the command context is not connected to the database the preflight checked");
        }
        verify(replay, never()).startRestoreReplay(any(), anyString(), anyList());
    }

    @Test
    void anAttemptStartedForAnotherDatasetOrSetIsRefused() {
        RecoveryReplayPreflight.Plan plan = plan(validArgs());
        for (RestoreAttempt other : List.of(
                new RestoreAttempt(UUID.fromString(ATTEMPT), "another-dataset", plan.set().erasureSetDigest(), 1,
                        RecoveryFixtures.CLOCK.instant()),
                new RestoreAttempt(UUID.fromString(ATTEMPT), DATASET, "0".repeat(64), 1,
                        RecoveryFixtures.CLOCK.instant()))) {
            when(restores.findAttempt(UUID.fromString(ATTEMPT))).thenReturn(Optional.of(other));

            assertThat(enabled().execute(plan, verdict())).isEqualTo(RecoveryReplayExit.ATTEMPT_MISMATCH);
        }
        verify(replay, never()).startRestoreReplay(any(), anyString(), anyList());
    }

    @Test
    void theSameAttemptDatasetAndSetResumes() {
        RecoveryReplayPreflight.Plan plan = plan(validArgs());
        when(restores.findAttempt(UUID.fromString(ATTEMPT))).thenReturn(Optional.of(new RestoreAttempt(
                UUID.fromString(ATTEMPT), DATASET, plan.set().erasureSetDigest(), USERS, RecoveryFixtures.CLOCK.instant())));
        acks(Map.of(), RestoreReplayVerdict.Status.COMPLETE);

        assertThat(enabled().execute(plan, verdict())).isEqualTo(RecoveryReplayExit.COMPLETE);
    }

    @Test
    void aFailedStartIsAnInternalFailureAndNeverComplete() {
        when(replay.startRestoreReplay(any(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("auth share failed; the start transaction rolled back"));

        assertThat(enabled().execute(plan(validArgs()), verdict())).isEqualTo(RecoveryReplayExit.INTERNAL);
        assertThat(written().path("status").asText()).isEqualTo("INTERNAL");
    }

    @Test
    void completeWhenEveryParticipantAndAuthAcknowledgedCountedSeparately() {
        acks(Map.of(), RestoreReplayVerdict.Status.COMPLETE);

        RecoveryReplayExit exit = enabled().execute(plan(validArgs()), verdict());

        assertThat(exit).isEqualTo(RecoveryReplayExit.COMPLETE);
        assertThat(exit.code()).isZero();
        JsonNode written = written();
        assertThat(written.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(written.path("exitCode").asInt()).isZero();
        assertThat(written.path("users").asInt()).isEqualTo(USERS);
        assertThat(written.path("auth").path("success").asLong()).isEqualTo(USERS);
        assertThat(written.path("participants").size()).isEqualTo(8);
        assertThat(written.path("participants").has("auth")).isFalse();
        written.path("participants").forEach(row -> assertThat(row.path("success").asLong()).isEqualTo(USERS));
        assertThat(written.path("coverage").path("ignoredFrontierVersions").asInt()).isZero();
        String statement = written.path("coverage").path("statement").asText();
        assertThat(statement).matches("erasure coverage verified through sequence 4 \\(frontier version sha256:[0-9a-f]{64}\\)");
        assertThat(FORBIDDEN_CLAIMS.matcher(written.path("coverage").toString()).find()).isFalse();
    }

    @Test
    void aFailedParticipantBlocksTheAttempt() {
        acks(Map.of("media", 1L), RestoreReplayVerdict.Status.BLOCKED);

        assertThat(enabled().execute(plan(validArgs()), verdict())).isEqualTo(RecoveryReplayExit.BLOCKED);
        assertThat(written().path("status").asText()).isEqualTo("BLOCKED");
    }

    @Test
    void missingAcknowledgementsTimeOutWithinTheBoundWhateverThePollInterval() {
        acks(Map.of(), RestoreReplayVerdict.Status.BLOCKED);
        // A poll interval far beyond the timeout never stretches the wait (review N5).
        environment.setProperty(RecoveryReplayPreflight.POLL_INTERVAL, "PT1H");
        long started = System.nanoTime();

        RecoveryReplayExit exit = enabled().execute(
                plan(args(evidence, trust, ATTEMPT, DATASET, TARGET, verdictFile, "--timeout-seconds=1")), verdict());

        assertThat(exit).isEqualTo(RecoveryReplayExit.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(written().path("reason").asText()).startsWith("timed out after 1s");
        assertThat(written().path("status").asText()).isEqualTo("TIMEOUT");
    }

    @Test
    void aVerdictThatCannotBeWrittenIsNeverReportedAsComplete() {
        acks(Map.of(), RestoreReplayVerdict.Status.COMPLETE);
        verdictFile = dir.resolve("missing-directory").resolve("verdict.json");

        assertThat(enabled().execute(plan(validArgs()), verdict())).isEqualTo(RecoveryReplayExit.INTERNAL);
        assertThat(Files.exists(verdictFile)).isFalse();
    }

    private void acks(Map<String, Long> failed, RestoreReplayVerdict.Status status) {
        UUID attempt = UUID.fromString(ATTEMPT);
        List<String> required = new ArrayList<>(AccountErasureApplicationService.DEFAULT_PARTICIPANTS);
        required.add(ErasureRestoreReplayService.AUTH_PARTICIPANT);
        Map<String, Long> success = new HashMap<>();
        required.forEach(name -> success.put(name, (long) USERS));
        when(restores.requiredParticipants(attempt)).thenReturn(required);
        when(restores.countAcksByService(attempt, "SUCCESS")).thenReturn(success);
        when(restores.countAcksByService(attempt, "FAILED")).thenReturn(failed);
        Map<String, Long> missing = status == RestoreReplayVerdict.Status.COMPLETE ? Map.of() : Map.of("gamification", 1L);
        when(replay.verdict(attempt)).thenReturn(new RestoreReplayVerdict(attempt, status, USERS, missing, failed,
                status == RestoreReplayVerdict.Status.COMPLETE ? null : "participant acknowledgements are missing or failed"));
    }

    private RecoveryReplayPreflight.Plan plan(String[] arguments) {
        RecoveryReplayArguments parsed = RecoveryReplayArguments.parse(arguments);
        return RecoveryFixtures.preflight(Target.of(TARGET)).check(environment, parsed, new RecoveryReplayVerdict(parsed));
    }

    private RecoveryReplayVerdict verdict() {
        RecoveryReplayArguments parsed = RecoveryReplayArguments.parse(validArgs());
        RecoveryReplayVerdict verdict = new RecoveryReplayVerdict(parsed);
        verdict.set(plan(validArgs()).set());
        return verdict;
    }

    private JsonNode written() {
        return RecoveryFixtures.readTree(verdictFile);
    }

    private String[] validArgs() {
        return args(evidence, trust, ATTEMPT, DATASET, TARGET, verdictFile);
    }

    private RecoveryReplayCommand enabled() {
        return command(() -> TARGET);
    }

    private RecoveryReplayCommand command(Supplier<String> identity) {
        return new RecoveryReplayCommand(replay, restores, identity);
    }
}
