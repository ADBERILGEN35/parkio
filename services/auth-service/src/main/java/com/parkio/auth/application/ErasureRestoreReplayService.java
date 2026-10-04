package com.parkio.auth.application;

import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.durable.ErasureSetDigest;
import com.parkio.auth.application.port.ErasureRestoreRepository;
import com.parkio.auth.application.port.ErasureRestoreRepository.RestoreAttempt;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.domain.event.UserErasureRestoreAcknowledgedEvent;
import com.parkio.auth.domain.event.UserErasureRestoreReplayRequestedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinator side of the restore replay (docs/architecture/erasure-restore-replay-contract.md):
 * starts a replay of a trusted erasure set for one recovery attempt and collects the
 * participants' attempt-bound ACKs. Off by default
 * ({@code parkio.privacy.account-erasure.restore-replay.enabled}); nothing in the service starts a
 * replay. Live erasure ACKs are not consulted and are not affected.
 */
@Service
public class ErasureRestoreReplayService {

    private static final Logger log = LoggerFactory.getLogger(ErasureRestoreReplayService.class);
    private static final Set<String> STATUSES = Set.of("SUCCESS", "FAILED");

    private final ErasureRestoreRepository restores;
    private final OutboxEventAppender outbox;
    private final InboxEventRepository inbox;
    private final Clock clock;
    private final boolean enabled;
    private final Set<String> participants;

    public ErasureRestoreReplayService(
            ErasureRestoreRepository restores,
            OutboxEventAppender outbox,
            InboxEventRepository inbox,
            Clock clock,
            @Value("${parkio.privacy.account-erasure.restore-replay.enabled:false}") boolean enabled,
            @Value("${parkio.privacy.account-erasure.participants:user,parking,media,moderation,gamification,notification,analytics,ai-validation}")
                    String participantsCsv) {
        this.restores = restores;
        this.outbox = outbox;
        this.inbox = inbox;
        this.clock = clock;
        this.enabled = enabled;
        this.participants = Arrays.stream(participantsCsv.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Records the attempt and queues one replay command per user, in one transaction. Starting the
     * same attempt again with the same dataset and erasure set changes nothing; the same attempt
     * id with another dataset or set is refused.
     */
    @Transactional
    public RestoreAttempt startRestoreReplay(UUID recoveryAttemptId, String restoredDatasetId,
                                             List<ErasureLedgerEntry> entries) {
        if (!enabled) {
            throw new IllegalStateException(
                    "restore replay is disabled (parkio.privacy.account-erasure.restore-replay.enabled=false)");
        }
        Objects.requireNonNull(recoveryAttemptId, "recoveryAttemptId");
        if (restoredDatasetId == null || restoredDatasetId.isBlank()) {
            throw new IllegalArgumentException("restoredDatasetId must not be blank");
        }
        String digest = ErasureSetDigest.of(entries);
        RestoreAttempt existing = restores.findAttempt(recoveryAttemptId).orElse(null);
        if (existing != null) {
            if (existing.restoredDatasetId().equals(restoredDatasetId) && existing.erasureSetDigest().equals(digest)) {
                return existing;
            }
            throw new IllegalStateException(
                    "recovery attempt " + recoveryAttemptId + " was started for another dataset or erasure set");
        }
        // PostgreSQL keeps microseconds: the attempt read back on a restart must equal this one.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        RestoreAttempt attempt = new RestoreAttempt(recoveryAttemptId, restoredDatasetId, digest, entries.size(), now);
        restores.insertAttempt(attempt, entries);
        for (ErasureLedgerEntry entry : entries) {
            outbox.append(new UserErasureRestoreReplayRequestedEvent(
                    UserErasureRestoreReplayRequestedEvent.eventIdFor(recoveryAttemptId, entry.authUserId()),
                    recoveryAttemptId, restoredDatasetId, digest, entry.authUserId(), entry.erasedAt(), now));
        }
        log.info("erasure restore replay started attempt={} users={} digest={}", recoveryAttemptId, entries.size(), digest);
        return attempt;
    }

    /**
     * Stores a participant's ACK if it belongs to a known attempt, a configured participant and a
     * user of the attempt, and echoes the attempt's dataset and digest. Anything else is ignored:
     * an ACK of a prior attempt, another dataset or another erasure set never counts.
     */
    @Transactional
    public void handleAcknowledgement(UserErasureRestoreAcknowledgedEvent event) {
        if (!enabled) {
            log.debug("erasure restore ack ignored attempt={} status=restore-replay-disabled", event.recoveryAttemptId());
            return;
        }
        if (!inbox.tryClaim(event.eventId(), UserErasureRestoreAcknowledgedEvent.TYPE, clock.instant())) {
            return;
        }
        String service = event.serviceName() == null ? "" : event.serviceName().trim().toLowerCase(Locale.ROOT);
        if (event.status() == null || !STATUSES.contains(event.status())) {
            // Rolls back the inbox claim too: the consumer retries and then dead-letters it.
            throw new IllegalArgumentException("restore ack status must be SUCCESS or FAILED: " + event.status());
        }
        String ignored = ignoredBecause(event, service);
        if (ignored != null) {
            log.warn("erasure restore ack ignored attempt={} service={} status={}",
                    event.recoveryAttemptId(), service, ignored);
            return;
        }
        restores.upsertAck(event.recoveryAttemptId(), event.authUserId(), service, event.status(), clock.instant());
    }

    /** {@code COMPLETE} only when every configured participant acknowledged every user with SUCCESS. */
    @Transactional(readOnly = true)
    public RestoreReplayVerdict verdict(UUID recoveryAttemptId) {
        RestoreAttempt attempt = restores.findAttempt(recoveryAttemptId).orElse(null);
        if (attempt == null) {
            return RestoreReplayVerdict.blocked(recoveryAttemptId, 0, Map.of(), Map.of(), "unknown recovery attempt");
        }
        Map<String, Long> succeeded = restores.countAcksByService(recoveryAttemptId, "SUCCESS");
        Map<String, Long> failed = restores.countAcksByService(recoveryAttemptId, "FAILED");
        Map<String, Long> missing = new LinkedHashMap<>();
        Map<String, Long> failedByParticipant = new LinkedHashMap<>();
        for (String participant : participants) {
            long outstanding = attempt.userCount() - succeeded.getOrDefault(participant, 0L);
            if (outstanding > 0) {
                missing.put(participant, outstanding);
            }
            if (failed.getOrDefault(participant, 0L) > 0) {
                failedByParticipant.put(participant, failed.get(participant));
            }
        }
        if (missing.isEmpty()) {
            return RestoreReplayVerdict.complete(recoveryAttemptId, attempt.userCount());
        }
        return RestoreReplayVerdict.blocked(recoveryAttemptId, attempt.userCount(), missing, failedByParticipant,
                "participant acknowledgements are missing or failed");
    }

    private String ignoredBecause(UserErasureRestoreAcknowledgedEvent event, String service) {
        if (!participants.contains(service)) {
            return "unknown-participant";
        }
        RestoreAttempt attempt = restores.findAttempt(event.recoveryAttemptId()).orElse(null);
        if (attempt == null) {
            return "unknown-attempt";
        }
        if (!attempt.restoredDatasetId().equals(event.restoredDatasetId())) {
            return "other-dataset";
        }
        if (!attempt.erasureSetDigest().equals(event.erasureSetDigest())) {
            return "other-erasure-set";
        }
        if (!restores.isAttemptUser(event.recoveryAttemptId(), event.authUserId())) {
            return "user-not-in-set";
        }
        return null;
    }
}
