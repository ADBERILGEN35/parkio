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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.application.ErasureRestoreReplayService;
import com.parkio.auth.application.RestoreReplayVerdict;
import com.parkio.auth.application.port.ErasureRestoreRepository;
import com.parkio.auth.application.port.ErasureRestoreRepository.RestoreAttempt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The recovery-replay command's checks, in isolation (owner decision P6 and its conditions, D1).
 * Every refusal must leave the replay unstarted and the verdict not COMPLETE; the real-PostgreSQL
 * paths are in {@link RecoveryReplayCommandPostgresIT}. Synthetic ids and keys only.
 */
class RecoveryReplayCommandTest {

    private static final Pattern FORBIDDEN_CLAIMS = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T|no later|absen|cutoff|complete coverage|all erasures|until now",
            Pattern.CASE_INSENSITIVE);

    @TempDir Path dir;

    private final ErasureRestoreReplayService replay = mock(ErasureRestoreReplayService.class);
    private final ErasureRestoreRepository restores = mock(ErasureRestoreRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);
    private Path evidence;
    private Path trust;
    private Path verdict;

    @BeforeEach
    void inputs() {
        evidence = RecoveryFixtures.trustedSet(dir, ATTEMPT, DATASET, TARGET);
        trust = RecoveryFixtures.trustDocument(dir, EVIDENCE_IDENTITY);
        verdict = dir.resolve("verdict.json");
        when(jdbc.queryForList(anyString(), eq(UUID.class))).thenReturn(List.of());
        when(restores.findAttempt(any())).thenReturn(Optional.empty());
    }

    @Test
    void theCommandExistsOnlyUnderTheRecoveryReplayProfile() {
        assertThat(RecoveryReplayCommand.class.getAnnotation(Profile.class).value())
                .containsExactly(RecoveryReplayLaunch.PROFILE);
    }

    @Test
    void theProfileWithoutTheFlagIsRefusedBeforeAnythingIsRead() {
        RecoveryReplayCommand.Outcome outcome = command(() -> TARGET, false, Map.of()).execute(validArgs());

        assertRefusedUntouched(outcome, RecoveryReplayExit.REFUSED);
        assertThat(outcome.verdict().get("reason").toString()).contains("restore-replay.enabled=false");
    }

    @Test
    void anEnabledDurableStoreWriterIsRefused() {
        for (String writer : RecoveryReplayCommand.writers(false, false, false, false).keySet()) {
            Map<String, Boolean> writers = new HashMap<>(RecoveryReplayCommand.writers(false, false, false, false));
            writers.put(writer, true);
            RecoveryReplayCommand.Outcome outcome = command(() -> TARGET, true, writers).execute(validArgs());

            assertRefusedUntouched(outcome, RecoveryReplayExit.REFUSED);
            assertThat(outcome.verdict().get("reason").toString()).contains(writer);
        }
    }

    @Test
    void thereIsNoCutoffOrReceiptOption() {
        for (String option : List.of("--required-through-sequence=4", "--receipt=receipt.json", "--recovery-cutoff=x")) {
            RecoveryReplayCommand.Outcome outcome = enabled(() -> TARGET).execute(
                    args(evidence, trust, ATTEMPT, DATASET, TARGET, verdict, option));

            assertRefusedUntouched(outcome, RecoveryReplayExit.REFUSED);
            assertThat(outcome.verdict().get("reason").toString()).startsWith("unknown option --");
        }
    }

    @Test
    void untrustedEvidenceIsRefusedBeforeTheDatabaseIsTouched() {
        for (String bundle : List.of("gap", "missing-frontier", "frontier-all-tampered", "tail-conflict",
                "frontier-newest-tampered", "listed-above-frontier")) {
            evidence = RecoveryFixtures.trustedSetWithBundle(dir, bundle);
            RecoveryReplayCommand.Outcome outcome = enabled(() -> TARGET).execute(validArgs());

            assertRefusedUntouched(outcome, RecoveryReplayExit.INVALID_EVIDENCE);
            assertThat(outcome.verdict().get("reason").toString()).as(bundle).startsWith("evidence does not verify: ");
            assertWrittenStatus("INVALID_EVIDENCE");
        }
    }

    @Test
    void aTrustDocumentForAnotherDatabaseRefusesTheEvidence() {
        trust = RecoveryFixtures.trustDocument(dir, "postgresql:7000000000000000002:parkio_auth");

        assertRefusedUntouched(enabled(() -> TARGET).execute(validArgs()), RecoveryReplayExit.INVALID_EVIDENCE);
    }

    @Test
    void anAttemptOrDatasetOtherThanTheTrustedSetIsRefused() {
        String otherAttempt = UUID.randomUUID().toString();
        assertRefusedUntouched(enabled(() -> TARGET).execute(
                args(evidence, trust, otherAttempt, DATASET, TARGET, verdict)), RecoveryReplayExit.ATTEMPT_MISMATCH);
        assertRefusedUntouched(enabled(() -> TARGET).execute(
                args(evidence, trust, ATTEMPT, "another-dataset", TARGET, verdict)), RecoveryReplayExit.ATTEMPT_MISMATCH);
    }

    @Test
    void theProductionIdentityAndAnAmbiguousIdentityAreRefused() {
        assertRefusedUntouched(enabled(() -> EVIDENCE_IDENTITY).execute(validArgs()), RecoveryReplayExit.TARGET_REFUSED);
        assertRefusedUntouched(enabled(() -> {
            throw new IllegalStateException("permission denied for pg_control_system");
        }).execute(validArgs()), RecoveryReplayExit.TARGET_REFUSED);
        assertRefusedUntouched(enabled(() -> null).execute(validArgs()), RecoveryReplayExit.TARGET_REFUSED);
        assertRefusedUntouched(enabled(() -> " ").execute(validArgs()), RecoveryReplayExit.TARGET_REFUSED);
    }

    @Test
    void aTargetOtherThanTheTicketsIsRefused() {
        assertRefusedUntouched(enabled(() -> "postgresql:7000000000000000123:parkio_auth").execute(validArgs()),
                RecoveryReplayExit.TARGET_REFUSED);
        evidence = RecoveryFixtures.trustedSet(dir, ATTEMPT, DATASET, "postgresql:7000000000000000123:parkio_auth");
        assertRefusedUntouched(enabled(() -> TARGET).execute(validArgs()), RecoveryReplayExit.TARGET_REFUSED);
        evidence = RecoveryFixtures.trustedSet(dir, ATTEMPT, DATASET, EVIDENCE_IDENTITY);
        assertRefusedUntouched(enabled(() -> EVIDENCE_IDENTITY).execute(
                args(evidence, trust, ATTEMPT, DATASET, EVIDENCE_IDENTITY, verdict)), RecoveryReplayExit.TARGET_REFUSED);
    }

    @Test
    void aDurablyRecordedErasureMissingFromTheTrustedSetIsRefused() {
        when(jdbc.queryForList(anyString(), eq(UUID.class))).thenReturn(List.of(UUID.randomUUID()));

        RecoveryReplayCommand.Outcome outcome = enabled(() -> TARGET).execute(validArgs());

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.INVALID_EVIDENCE);
        assertThat(outcome.verdict().get("reason").toString())
                .isEqualTo("the trusted set lacks 1 erasure(s) the restored auth database marks DURABLY_RECORDED;"
                        + " the evidence is older than the backup");
        verify(replay, never()).startRestoreReplay(any(), anyString(), anyList());
        assertWrittenStatus("INVALID_EVIDENCE");
    }

    @Test
    void anAttemptStartedForAnotherDatasetIsRefused() {
        when(restores.findAttempt(UUID.fromString(ATTEMPT))).thenReturn(Optional.of(
                new RestoreAttempt(UUID.fromString(ATTEMPT), "another-dataset", "0".repeat(64), 1, clock.instant())));

        RecoveryReplayCommand.Outcome outcome = enabled(() -> TARGET).execute(validArgs());

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.ATTEMPT_MISMATCH);
        verify(replay, never()).startRestoreReplay(any(), anyString(), anyList());
    }

    @Test
    void aFailedStartIsAnInternalFailureAndNeverComplete() {
        when(replay.startRestoreReplay(any(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("auth share failed; the start transaction rolled back"));

        RecoveryReplayCommand.Outcome outcome = enabled(() -> TARGET).execute(validArgs());

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.INTERNAL);
        assertWrittenStatus("INTERNAL");
    }

    @Test
    void completeWhenEveryParticipantAndAuthAcknowledgedCountedSeparately() throws Exception {
        acks(Map.of(), RestoreReplayVerdict.Status.COMPLETE);

        RecoveryReplayCommand.Outcome outcome = enabled(() -> TARGET).execute(validArgs());

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.COMPLETE);
        assertThat(outcome.exitCode()).isZero();
        JsonNode written = RecoveryFixtures.readTree(verdict);
        assertThat(written.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(written.path("exitCode").asInt()).isZero();
        assertThat(written.path("users").asInt()).isEqualTo(USERS);
        assertThat(written.path("auth").path("success").asLong()).isEqualTo(USERS);
        assertThat(written.path("participants").size()).isEqualTo(8);
        assertThat(written.path("participants").has("auth")).isFalse();
        written.path("participants").forEach(row -> assertThat(row.path("success").asLong()).isEqualTo(USERS));
        String statement = written.path("coverage").path("statement").asText();
        assertThat(statement).matches("erasure coverage verified through sequence 4 \\(frontier version sha256:[0-9a-f]{64}\\)");
        assertThat(FORBIDDEN_CLAIMS.matcher(statement).find()).isFalse();
        assertThat(FORBIDDEN_CLAIMS.matcher(written.path("coverage").toString()).find()).isFalse();
    }

    @Test
    void aFailedParticipantBlocksTheAttempt() {
        acks(Map.of("media", 1L), RestoreReplayVerdict.Status.BLOCKED);

        RecoveryReplayCommand.Outcome outcome = enabled(() -> TARGET).execute(validArgs());

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.BLOCKED);
        assertWrittenStatus("BLOCKED");
    }

    @Test
    void missingAcknowledgementsTimeOutWithinTheBound() {
        acks(Map.of(), RestoreReplayVerdict.Status.BLOCKED);
        long started = System.nanoTime();

        RecoveryReplayCommand.Outcome outcome = enabled(() -> TARGET).execute(
                args(evidence, trust, ATTEMPT, DATASET, TARGET, verdict, "--timeout-seconds=1"));

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(RecoveryFixtures.readTree(verdict).path("reason").asText()).startsWith("timed out after 1s");
        assertWrittenStatus("TIMEOUT");
    }

    @Test
    void aVerdictThatCannotBeWrittenIsNeverReportedAsComplete() {
        acks(Map.of(), RestoreReplayVerdict.Status.COMPLETE);
        verdict = dir.resolve("missing-directory").resolve("verdict.json");

        RecoveryReplayCommand.Outcome outcome = enabled(() -> TARGET).execute(validArgs());

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.INTERNAL);
        assertThat(Files.exists(verdict)).isFalse();
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

    private String[] validArgs() {
        return args(evidence, trust, ATTEMPT, DATASET, TARGET, verdict);
    }

    private RecoveryReplayCommand enabled(Supplier<String> identity) {
        return command(identity, true, RecoveryReplayCommand.writers(false, false, false, false));
    }

    private RecoveryReplayCommand command(Supplier<String> identity, boolean enabled, Map<String, Boolean> writers) {
        return new RecoveryReplayCommand(replay, restores, jdbc, clock, identity, enabled, writers, Duration.ofMillis(100));
    }

    private void assertRefusedUntouched(RecoveryReplayCommand.Outcome outcome, RecoveryReplayExit expected) {
        assertThat(outcome.exit()).isEqualTo(expected);
        assertThat(outcome.exitCode()).isNotZero();
        assertThat(outcome.verdict().get("status")).isEqualTo(expected.name());
        verify(replay, never()).startRestoreReplay(any(), anyString(), anyList());
        verify(replay, never()).verdict(any());
        if (Files.exists(verdict)) {
            assertThat(RecoveryFixtures.readTree(verdict).path("status").asText()).isNotEqualTo("COMPLETE");
        }
        if (expected == RecoveryReplayExit.REFUSED || expected == RecoveryReplayExit.INVALID_EVIDENCE) {
            verifyNoInteractions(restores);
        }
    }

    private void assertWrittenStatus(String status) {
        assertThat(RecoveryFixtures.readTree(verdict).path("status").asText()).isEqualTo(status);
    }
}
