package com.parkio.auth.infrastructure.recovery;

import com.parkio.auth.application.ErasureRestoreReplayService;
import com.parkio.auth.application.RestoreReplayVerdict;
import com.parkio.auth.application.port.ErasureRestoreRepository;
import com.parkio.auth.application.port.ErasureRestoreRepository.RestoreAttempt;
import com.parkio.auth.infrastructure.persistence.PostgresDatabaseIdentity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The part of the recovery replay that needs auth's runtime (owner decision P6, 2026-10-06). It
 * runs once, in the command context {@link RecoveryReplayLaunch} starts only after
 * {@link RecoveryReplayPreflight} accepted everything that can be refused before a context exists.
 * In order, it:
 *
 * <ol>
 *   <li>re-reads the connected database's identity through the context's datasource and refuses
 *       anything other than the database the preflight checked (22);</li>
 *   <li>refuses an attempt id already used for another dataset or erasure set (23);</li>
 *   <li>starts the replay and waits, bounded by {@code --timeout-seconds}, for the verdict: COMPLETE
 *       (0) only when every participant and auth acknowledged every user, BLOCKED (24) on a FAILED
 *       acknowledgement, TIMEOUT (25) otherwise.</li>
 * </ol>
 *
 * <p>The restored auth database is applied to the isolated target before the command runs, and
 * nothing here exposes it: the command has no network endpoint. The verdict JSON reports coverage
 * only as "erasure coverage verified through sequence N (frontier version V)" and counts auth's
 * acknowledgements separately from the participants'.
 */
@Component
@Profile(RecoveryReplayLaunch.PROFILE)
public class RecoveryReplayCommand {

    private static final Logger log = LoggerFactory.getLogger(RecoveryReplayCommand.class);

    private final ErasureRestoreReplayService replay;
    private final ErasureRestoreRepository restores;
    private final Supplier<String> connectedIdentity;

    @Autowired
    public RecoveryReplayCommand(ErasureRestoreReplayService replay, ErasureRestoreRepository restores,
                                 JdbcTemplate jdbc) {
        this(replay, restores, () -> PostgresDatabaseIdentity.of(jdbc));
    }

    RecoveryReplayCommand(ErasureRestoreReplayService replay, ErasureRestoreRepository restores,
                          Supplier<String> connectedIdentity) {
        this.replay = replay;
        this.restores = restores;
        this.connectedIdentity = connectedIdentity;
    }

    /** Runs the replay of {@code plan} once; never throws. Returns the exit after writing the verdict. */
    RecoveryReplayExit execute(RecoveryReplayPreflight.Plan plan, RecoveryReplayVerdict verdict) {
        RecoveryReplayExit exit;
        try {
            exit = run(plan, verdict);
        } catch (RecoveryReplayRefusal refusal) {
            exit = refusal.exit();
            verdict.put("reason", refusal.getMessage());
        } catch (RuntimeException ex) {
            exit = RecoveryReplayExit.INTERNAL;
            verdict.put("reason", "internal failure: " + ex.getClass().getSimpleName());
            log.error("recovery replay failed", ex);
        }
        exit = verdict.finish(exit, plan.arguments().verdictOut());
        log.info("recovery replay finished status={} exitCode={} reason={}", exit, exit.code(), verdict.get("reason"));
        return exit;
    }

    private RecoveryReplayExit run(RecoveryReplayPreflight.Plan plan, RecoveryReplayVerdict verdict) {
        RecoveryReplayArguments arguments = plan.arguments();
        String connected;
        try {
            connected = connectedIdentity.get();
        } catch (RuntimeException ex) {
            connected = null;
        }
        if (!plan.connectedIdentity().equals(connected)) {
            throw new RecoveryReplayRefusal(RecoveryReplayExit.TARGET_REFUSED,
                    "the command context is not connected to the database the preflight checked");
        }
        RestoreAttempt existing = restores.findAttempt(arguments.attempt()).orElse(null);
        if (existing != null && (!existing.restoredDatasetId().equals(arguments.dataset())
                || !existing.erasureSetDigest().equals(plan.set().erasureSetDigest()))) {
            throw new RecoveryReplayRefusal(RecoveryReplayExit.ATTEMPT_MISMATCH,
                    "recovery attempt was started for another dataset or erasure set");
        }

        replay.startRestoreReplay(arguments.attempt(), arguments.dataset(), plan.set().entries());
        log.info("recovery replay started attempt={} users={} {}", arguments.attempt(), plan.set().entries().size(),
                plan.set().statement());
        return await(plan, verdict);
    }

    private RecoveryReplayExit await(RecoveryReplayPreflight.Plan plan, RecoveryReplayVerdict verdict) {
        RecoveryReplayArguments arguments = plan.arguments();
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
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                verdict.put("reason", "timed out after " + arguments.timeout().toSeconds()
                        + "s waiting for acknowledgements; missing " + current.missing());
                return RecoveryReplayExit.TIMEOUT;
            }
            try {
                // Never past the deadline, whatever the poll interval.
                Thread.sleep(Math.max(1, Math.min(plan.pollInterval().toMillis(), remaining / 1_000_000L + 1)));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for acknowledgements", ex);
            }
        }
    }

    /** ACK rows per required participant, and auth's own share separately. */
    private void counts(UUID attempt, RestoreReplayVerdict current, RecoveryReplayVerdict verdict) {
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
}
