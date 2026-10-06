package com.parkio.auth.infrastructure.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.parkio.auth.application.ErasureRestoreReplayService;
import com.parkio.auth.application.RestoreReplayVerdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableEvidenceException;
import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedErasureSet;
import com.parkio.auth.application.durable.TrustedErasureSetDocument;
import com.parkio.auth.application.port.ErasureRestoreRepository;
import com.parkio.auth.application.port.ErasureRestoreRepository.RestoreAttempt;
import com.parkio.auth.infrastructure.persistence.PostgresDatabaseIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one entry point that starts a restore replay (owner decision P6, 2026-10-06): a one-shot
 * command, run only by the isolated restore path, with no network endpoint. It exists only under
 * the {@value RecoveryReplayLaunch#PROFILE} profile and acts only when
 * {@code parkio.privacy.account-erasure.restore-replay.enabled} is also true. In order, it:
 *
 * <ol>
 *   <li>refuses when any durable-store writer is enabled, so a restored copy never writes into the
 *       real evidence store;</li>
 *   <li>re-verifies the trusted-set file's embedded evidence bundle against its own trust document
 *       and refuses any difference from the coverage and set the file states;</li>
 *   <li>checks the attempt and dataset against the file;</li>
 *   <li>reads the connected database's identity and refuses it when it is unreadable, is the
 *       production identity pinned in the trust document, or is not the ticket's target;</li>
 *   <li>checks the backup anchor: every erasure the restored auth database marks
 *       {@code DURABLY_RECORDED} must be in the trusted set;</li>
 *   <li>refuses an attempt id already used for another dataset or set;</li>
 *   <li>starts the replay and waits, bounded, for the verdict.</li>
 * </ol>
 *
 * <p>The restored auth database is applied to the isolated target before this command runs. It
 * stays unexposed (expose gate CLOSED, no published ports) until the verdict is COMPLETE. The
 * verdict JSON reports coverage only as "erasure coverage verified through sequence N (frontier
 * version V)" and counts auth's acknowledgements separately from the participants'.
 */
@Component
@Profile(RecoveryReplayLaunch.PROFILE)
public class RecoveryReplayCommand {

    static final String VERDICT_FORMAT = "parkio-recovery-replay-verdict";
    private static final Logger log = LoggerFactory.getLogger(RecoveryReplayCommand.class);
    private static final String DURABLY_RECORDED_USERS = """
            SELECT DISTINCT auth_user_id FROM erasure_requests WHERE durable_recording_status = 'DURABLY_RECORDED'
            """;

    /** What the command did: the exit code and the verdict document it wrote. */
    public record Outcome(RecoveryReplayExit exit, Map<String, Object> verdict) {
        public int exitCode() {
            return exit.code();
        }
    }

    /** A refusal with the exit code it maps to. */
    private static final class Stop extends RuntimeException {
        private final RecoveryReplayExit exit;

        Stop(RecoveryReplayExit exit, String message) {
            super(message);
            this.exit = exit;
        }
    }

    private final ErasureRestoreReplayService replay;
    private final ErasureRestoreRepository restores;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Supplier<String> connectedIdentity;
    private final Map<String, Boolean> writers;
    private final boolean restoreReplayEnabled;
    private final Duration pollInterval;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Autowired
    public RecoveryReplayCommand(
            ErasureRestoreReplayService replay,
            ErasureRestoreRepository restores,
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${parkio.privacy.account-erasure.restore-replay.enabled:false}") boolean restoreReplayEnabled,
            @Value("${parkio.privacy.account-erasure.durable-recording-enabled:false}") boolean durableRecording,
            @Value("${parkio.privacy.account-erasure.durable-recording-retry-worker-enabled:false}") boolean retryWorker,
            @Value("${parkio.privacy.account-erasure.durable-store.object-lock.enabled:false}") boolean objectLockStore,
            @Value("${parkio.privacy.account-erasure.durable-store.checkpoint.enabled:false}") boolean checkpoints,
            @Value("${parkio.privacy.account-erasure.recovery-replay.poll-interval:PT5S}") Duration pollInterval) {
        this(replay, restores, jdbc, clock, () -> PostgresDatabaseIdentity.of(jdbc), restoreReplayEnabled,
                writers(durableRecording, retryWorker, objectLockStore, checkpoints), pollInterval);
    }

    RecoveryReplayCommand(ErasureRestoreReplayService replay, ErasureRestoreRepository restores, JdbcTemplate jdbc,
                          Clock clock, Supplier<String> connectedIdentity, boolean restoreReplayEnabled,
                          Map<String, Boolean> writers, Duration pollInterval) {
        this.replay = replay;
        this.restores = restores;
        this.jdbc = jdbc;
        this.clock = clock;
        this.connectedIdentity = connectedIdentity;
        this.restoreReplayEnabled = restoreReplayEnabled;
        this.writers = Map.copyOf(writers);
        this.pollInterval = pollInterval;
    }

    static Map<String, Boolean> writers(boolean durableRecording, boolean retryWorker, boolean objectLockStore,
                                        boolean checkpoints) {
        Map<String, Boolean> writers = new LinkedHashMap<>();
        writers.put("parkio.privacy.account-erasure.durable-recording-enabled", durableRecording);
        writers.put("parkio.privacy.account-erasure.durable-recording-retry-worker-enabled", retryWorker);
        writers.put("parkio.privacy.account-erasure.durable-store.object-lock.enabled", objectLockStore);
        writers.put("parkio.privacy.account-erasure.durable-store.checkpoint.enabled", checkpoints);
        return writers;
    }

    /** Runs the command once; never throws. The caller exits with {@link Outcome#exitCode()}. */
    public Outcome execute(String[] args) {
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("format", VERDICT_FORMAT);
        verdict.put("version", 1);
        RecoveryReplayArguments arguments = null;
        RecoveryReplayExit exit;
        try {
            if (!restoreReplayEnabled) {
                throw new Stop(RecoveryReplayExit.REFUSED,
                        "restore replay is disabled (parkio.privacy.account-erasure.restore-replay.enabled=false)");
            }
            List<String> enabledWriters = writers.entrySet().stream()
                    .filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
            if (!enabledWriters.isEmpty()) {
                throw new Stop(RecoveryReplayExit.REFUSED,
                        "a durable-store writer is enabled; a restored copy must not write evidence: " + enabledWriters);
            }
            try {
                arguments = RecoveryReplayArguments.parse(args);
            } catch (RecoveryReplayArguments.Refusal refusal) {
                throw new Stop(RecoveryReplayExit.REFUSED, refusal.getMessage());
            }
            verdict.put("recoveryAttemptId", arguments.attempt().toString());
            verdict.put("restoredDatasetId", arguments.dataset());
            exit = run(arguments, verdict);
        } catch (Stop stop) {
            exit = stop.exit;
            verdict.put("reason", stop.getMessage());
        } catch (RuntimeException ex) {
            exit = RecoveryReplayExit.INTERNAL;
            verdict.put("reason", "internal failure: " + ex.getClass().getSimpleName());
            log.error("recovery replay failed", ex);
        }
        verdict.put("status", exit.name());
        verdict.put("exitCode", exit.code());
        if (arguments != null && !write(arguments.verdictOut(), verdict)) {
            // Without a verdict file nothing can open the expose gate; never report success then.
            exit = RecoveryReplayExit.INTERNAL;
            verdict.put("status", exit.name());
            verdict.put("exitCode", exit.code());
            verdict.put("reason", "the verdict file could not be written");
        }
        log.info("recovery replay finished status={} exitCode={} reason={}", exit, exit.code(), verdict.get("reason"));
        return new Outcome(exit, verdict);
    }

    private RecoveryReplayExit run(RecoveryReplayArguments arguments, Map<String, Object> verdict) {
        EvidenceTrust trust = trust(arguments.trust());
        TrustedErasureSetDocument document;
        TrustedErasureSet set;
        try {
            document = TrustedErasureSetDocument.parse(read(arguments.evidence(), "trusted-set file"));
            set = document.verify(new DurableErasureEvidenceVerifier(trust, clock.instant()), trust.databaseIdentity());
        } catch (DurableEvidenceException ex) {
            throw new Stop(RecoveryReplayExit.INVALID_EVIDENCE, "evidence does not verify: " + ex.getMessage());
        }
        verdict.put("erasureSetDigest", set.erasureSetDigest());
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("verifiedThroughSequence", set.verifiedThroughSequence());
        coverage.put("frontierVersion", set.frontierVersion());
        coverage.put("latestTrustedCheckpoint", set.latestTrustedCheckpoint());
        coverage.put("statement", set.statement());
        verdict.put("coverage", coverage);
        verdict.put("users", set.entries().size());

        if (!document.recoveryAttemptId().equals(arguments.attempt().toString())
                || !document.restoredDatasetId().equals(arguments.dataset())) {
            throw new Stop(RecoveryReplayExit.ATTEMPT_MISMATCH,
                    "the attempt or dataset does not match the trusted-set file");
        }
        checkTarget(arguments, document, trust);
        checkAnchor(set);
        RestoreAttempt existing = restores.findAttempt(arguments.attempt()).orElse(null);
        if (existing != null && (!existing.restoredDatasetId().equals(arguments.dataset())
                || !existing.erasureSetDigest().equals(set.erasureSetDigest()))) {
            throw new Stop(RecoveryReplayExit.ATTEMPT_MISMATCH,
                    "recovery attempt was started for another dataset or erasure set");
        }

        replay.startRestoreReplay(arguments.attempt(), arguments.dataset(), set.entries());
        log.info("recovery replay started attempt={} users={} {}", arguments.attempt(), set.entries().size(),
                set.statement());
        return await(arguments, set, verdict);
    }

    private void checkTarget(RecoveryReplayArguments arguments, TrustedErasureSetDocument document, EvidenceTrust trust) {
        String connected;
        try {
            connected = connectedIdentity.get();
        } catch (RuntimeException ex) {
            throw new Stop(RecoveryReplayExit.TARGET_REFUSED, "the connected database identity is unreadable");
        }
        if (connected == null || connected.isBlank()) {
            throw new Stop(RecoveryReplayExit.TARGET_REFUSED, "the connected database identity is unreadable");
        }
        if (connected.equals(trust.databaseIdentity()) || arguments.targetIdentity().equals(trust.databaseIdentity())) {
            throw new Stop(RecoveryReplayExit.TARGET_REFUSED,
                    "the target is the production database identity pinned in the trust document");
        }
        if (!connected.equals(arguments.targetIdentity()) || !document.targetIdentity().equals(arguments.targetIdentity())) {
            throw new Stop(RecoveryReplayExit.TARGET_REFUSED,
                    "the connected database is not the isolated target named by the ticket");
        }
    }

    /** Evidence older than the backup lacks erasures the restored auth database already recorded. */
    private void checkAnchor(TrustedErasureSet set) {
        Set<UUID> trusted = set.entries().stream().map(ErasureLedgerEntry::authUserId).collect(Collectors.toSet());
        List<UUID> recorded = jdbc.queryForList(DURABLY_RECORDED_USERS, UUID.class);
        long missing = recorded.stream().filter(user -> !trusted.contains(user)).count();
        if (missing > 0) {
            throw new Stop(RecoveryReplayExit.INVALID_EVIDENCE, "the trusted set lacks " + missing
                    + " erasure(s) the restored auth database marks DURABLY_RECORDED; the evidence is older than"
                    + " the backup");
        }
    }

    private RecoveryReplayExit await(RecoveryReplayArguments arguments, TrustedErasureSet set,
                                     Map<String, Object> verdict) {
        // Monotonic: a wall-clock step must not stretch or cut the bounded wait.
        long deadline = System.nanoTime() + arguments.timeout().toNanos();
        while (true) {
            RestoreReplayVerdict current = replay.verdict(arguments.attempt());
            counts(arguments.attempt(), current, verdict);
            if (current.status() == RestoreReplayVerdict.Status.COMPLETE) {
                return RecoveryReplayExit.COMPLETE;
            }
            if (!current.failed().isEmpty()) {
                verdict.put("reason", "a participant reported FAILED: " + current.failed().keySet());
                return RecoveryReplayExit.BLOCKED;
            }
            if (System.nanoTime() - deadline >= 0) {
                verdict.put("reason", "timed out after " + arguments.timeout().toSeconds()
                        + "s waiting for acknowledgements; missing " + current.missing());
                return RecoveryReplayExit.TIMEOUT;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for acknowledgements", ex);
            }
        }
    }

    /** ACK rows per required participant, and auth's own share separately. */
    private void counts(UUID attempt, RestoreReplayVerdict current, Map<String, Object> verdict) {
        Map<String, Long> succeeded = restores.countAcksByService(attempt, "SUCCESS");
        Map<String, Long> failed = restores.countAcksByService(attempt, "FAILED");
        Map<String, Object> participants = new LinkedHashMap<>();
        Map<String, Object> auth = null;
        for (String participant : restores.requiredParticipants(attempt)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("success", succeeded.getOrDefault(participant, 0L));
            row.put("failed", failed.getOrDefault(participant, 0L));
            row.put("missing", current.missing().getOrDefault(participant, 0L));
            if (ErasureRestoreReplayService.AUTH_PARTICIPANT.equals(participant)) {
                auth = row;
            } else {
                participants.put(participant, row);
            }
        }
        verdict.put("participants", participants);
        verdict.put("auth", auth);
    }

    private static EvidenceTrust trust(Path path) {
        try {
            return EvidenceTrust.parse(read(path, "trust document"));
        } catch (DurableEvidenceException | IllegalArgumentException ex) {
            throw new Stop(RecoveryReplayExit.INVALID_EVIDENCE, "trust document is invalid: " + ex.getMessage());
        }
    }

    private static byte[] read(Path path, String label) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new Stop(RecoveryReplayExit.REFUSED, label + " cannot be read");
        }
    }

    private boolean write(Path target, Map<String, Object> verdict) {
        try {
            Path absolute = target.toAbsolutePath();
            Path temporary = Files.createTempFile(absolute.getParent(), ".recovery-verdict-", ".json");
            Files.write(temporary, json.writeValueAsBytes(verdict));
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException ex) {
            log.error("recovery replay verdict could not be written", ex);
            return false;
        }
    }
}
