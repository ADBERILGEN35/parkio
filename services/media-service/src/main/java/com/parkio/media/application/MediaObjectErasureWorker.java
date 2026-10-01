package com.parkio.media.application;

import com.parkio.media.application.port.ErasureAckOutbox;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.application.port.MediaStoragePort.StoredVersion;
import com.parkio.media.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.Claim;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.Job;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.ObjectWrite;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.StoredMedia;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 2 of media account erasure (docs/architecture/erasure-ack-outbox-contract.md).
 *
 * <p>Each attempt claims the job (token + lease) and works outside any database transaction: for
 * each media row of the user it lists the stored versions and delete markers of exactly that key
 * one page at a time, removes each by version id, and requires a fresh listing to be empty before
 * a short transaction deletes that row and its validation results; with every row gone it empties
 * the owner key namespace the same way. A delete call that returns normally is not taken as
 * proof: a version listed again after its delete fails the attempt.
 *
 * <p>The attempt checks its deadline ({@code attempt-budget-ms}) before every storage call, so it
 * ends at most one storage call (bounded by the storage call timeout) after the budget, leaving
 * the remaining work pending and durable. The lease must outlast that (checked at startup).
 *
 * <p>Recorded object writes of the user ({@code media_object_writes}, V16) are settled next. An
 * upload records its PUT before sending it; a PUT whose outcome is unknown may still be applied by
 * the store at any later time, and no documented bound limits when. Such a write is settled only
 * once its object has been observed (and removed): until then the job stays pending, records why,
 * and is retried with backoff. A write the store confirmed is settled once its object is confirmed
 * gone.
 *
 * <p>The SUCCESS ACK is queued in one transaction that first takes the owner's erasure fence
 * exclusively (no media write transaction of the owner can be open or admitted), then requires
 * the claim to be held and unexpired (an expired or reclaimed claim never finalizes), re-checks
 * that the owner has no media row and no recorded object write, deletes late idempotency records
 * and the job, and appends the ACK. Every failed attempt is recorded on the job (attempts, last
 * error, backoff); the scheduled poll claims due jobs and jobs whose claim expired.
 */
@Component
public class MediaObjectErasureWorker {

    /** Result of one processing attempt. */
    public enum Outcome {
        /** Every object confirmed gone; the SUCCESS ACK is queued and the job deleted. */
        ACK_QUEUED,
        /** Something is not confirmed yet, the attempt failed, or another attempt holds the job. */
        RETRY_SCHEDULED,
        /** The job does not exist (finished, or never opened). */
        NOT_PENDING
    }

    private enum Progress { CONFIRMED_ABSENT, BUDGET_SPENT }

    /** Result of settling one recorded object write. */
    private enum WriteProgress { SETTLED, OUTCOME_UNKNOWN, BUDGET_SPENT }

    /** Recorded writes looked at per attempt; more stay recorded and keep SUCCESS blocked. */
    static final int WRITE_BATCH = 100;

    /** Time the lease keeps for the database work after the last storage call. */
    static final Duration COMPLETION_MARGIN = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(MediaObjectErasureWorker.class);

    private final MediaErasureJobStore jobs;
    private final MediaStoragePort storage;
    private final ErasureAckOutbox ackOutbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean schedulingEnabled;
    private final int batchSize;
    private final Duration lease;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Duration attemptBudget;
    private final Counter objectFailures;
    private final Counter attemptFailures;
    private final Counter budgetStops;

    public MediaObjectErasureWorker(
            MediaErasureJobStore jobs,
            MediaStoragePort storage,
            ErasureAckOutbox ackOutbox,
            PlatformTransactionManager transactionManager,
            Clock clock,
            MeterRegistry registry,
            boolean schedulingEnabled,
            int batchSize,
            long leaseMs,
            long baseBackoffMs,
            long maxBackoffMs,
            long attemptBudgetMs) {
        this(jobs, storage, ackOutbox, transactionManager, clock, registry, schedulingEnabled, batchSize, leaseMs,
                baseBackoffMs, maxBackoffMs, attemptBudgetMs, Duration.ofSeconds(15));
    }

    @Autowired
    public MediaObjectErasureWorker(
            MediaErasureJobStore jobs,
            MediaStoragePort storage,
            ErasureAckOutbox ackOutbox,
            PlatformTransactionManager transactionManager,
            Clock clock,
            MeterRegistry registry,
            @Value("${parkio.media.erasure-worker.enabled:true}") boolean schedulingEnabled,
            @Value("${parkio.media.erasure-worker.batch-size:20}") int batchSize,
            @Value("${parkio.media.erasure-worker.lease-ms:120000}") long leaseMs,
            @Value("${parkio.media.erasure-worker.base-backoff-ms:5000}") long baseBackoffMs,
            @Value("${parkio.media.erasure-worker.max-backoff-ms:900000}") long maxBackoffMs,
            @Value("${parkio.media.erasure-worker.attempt-budget-ms:60000}") long attemptBudgetMs,
            @Value("${parkio.media.storage.call-timeout:15s}") Duration storageCallTimeout) {
        this.jobs = jobs;
        this.storage = storage;
        this.ackOutbox = ackOutbox;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.schedulingEnabled = schedulingEnabled;
        this.batchSize = batchSize;
        this.lease = Duration.ofMillis(leaseMs);
        this.baseBackoff = Duration.ofMillis(baseBackoffMs);
        this.maxBackoff = Duration.ofMillis(maxBackoffMs);
        this.attemptBudget = Duration.ofMillis(attemptBudgetMs);
        requirePositive("parkio.media.erasure-worker.attempt-budget-ms", attemptBudget);
        requirePositive("parkio.media.erasure-worker.lease-ms", lease);
        requirePositive("parkio.media.storage.call-timeout", storageCallTimeout);
        Duration needed = attemptBudget.plus(storageCallTimeout.multipliedBy(2)).plus(COMPLETION_MARGIN);
        if (lease.compareTo(needed) < 0) {
            throw new IllegalStateException("parkio.media.erasure-worker.lease-ms (" + lease.toMillis()
                    + ") must be at least attempt-budget-ms + 2 x parkio.media.storage.call-timeout + "
                    + COMPLETION_MARGIN.toSeconds() + "s (" + needed.toMillis() + ")");
        }
        this.objectFailures = Counter.builder("parkio.media.erasure.object.delete.failed")
                .description("Stored objects not confirmed deleted during account erasure (retried)")
                .register(registry);
        this.attemptFailures = Counter.builder("parkio.media.erasure.attempt.failed")
                .description("Media erasure attempts that failed outside object deletion (retried)")
                .register(registry);
        this.budgetStops = Counter.builder("parkio.media.erasure.attempt.budget_exhausted")
                .description("Media erasure attempts that stopped at their time budget with work left (continued)")
                .register(registry);
        Gauge.builder("parkio.media.erasure.jobs.pending", jobs, MediaErasureJobStore::countPendingJobs)
                .description("Media erasure jobs still waiting for confirmed object deletion (no SUCCESS yet)")
                .register(registry);
        Gauge.builder("parkio.media.object_writes.outcome_unknown", jobs, MediaErasureJobStore::countWritesOfUnknownOutcome)
                .description("Recorded object writes whose outcome is unknown; an erasure of their owner waits on them")
                .register(registry);
    }

    /** Claims due jobs, and jobs whose claim expired, and attempts each (crash recovery, outages, backoff). */
    @Scheduled(fixedDelayString = "${parkio.media.erasure-worker.poll-interval-ms:30000}")
    public void processDue() {
        if (!schedulingEnabled) {
            return;
        }
        Instant now = clock.instant();
        for (Claim claim : jobs.claimDue(batchSize, now, now.plus(lease))) {
            process(claim.ackEventId(), claim.token());
        }
    }

    /**
     * Claims the job and attempts it once. A failed attempt does not throw: it is recorded on the
     * job, which stays pending with backoff, and no SUCCESS is queued. If another attempt holds an
     * unexpired claim on the job, this call leaves it to that attempt.
     */
    public Outcome process(UUID jobId) {
        Optional<UUID> token;
        try {
            Instant now = clock.instant();
            token = jobs.claim(jobId, now, now.plus(lease));
        } catch (RuntimeException failure) {
            attemptFailures.increment();
            log.warn("media erasure job {} could not be claimed ({}); it stays pending with no SUCCESS",
                    jobId, typesOf(failure));
            log.debug("media erasure job {} claim failure", jobId, failure);
            return Outcome.RETRY_SCHEDULED;
        }
        if (token.isEmpty()) {
            return jobs.find(jobId).isPresent() ? Outcome.RETRY_SCHEDULED : Outcome.NOT_PENDING;
        }
        return process(jobId, token.get());
    }

    private Outcome process(UUID jobId, UUID token) {
        Integer attempts = null;
        try {
            Optional<Job> found = jobs.find(jobId);
            if (found.isEmpty()) {
                return Outcome.NOT_PENDING;
            }
            attempts = found.get().attempts();
            return attempt(found.get(), token);
        } catch (RuntimeException failure) {
            attemptFailures.increment();
            recordAttemptFailure(jobId, token, attempts, failure);
            return Outcome.RETRY_SCHEDULED;
        }
    }

    private Outcome attempt(Job job, UUID token) {
        Instant deadline = clock.instant().plus(attemptBudget);
        int failures = 0;
        boolean budgetSpent = false;
        String firstFailure = null;
        for (StoredMedia media : jobs.remainingMedia(job.authUserId())) {
            if (expired(deadline)) {
                budgetSpent = true;
                break;
            }
            try {
                if (eraseVersions(() -> storage.versionsOf(media.bucket(), media.objectKey()), deadline)
                        == Progress.BUDGET_SPENT) {
                    budgetSpent = true;
                    break;
                }
                tx.executeWithoutResult(status -> jobs.deleteMedia(media.mediaId()));
            } catch (RuntimeException e) {
                failures++;
                objectFailures.increment();
                if (firstFailure == null) {
                    firstFailure = "media " + media.mediaId() + ": " + reasonOf(e);
                }
            }
        }
        if (failures == 0 && !budgetSpent) {
            // Uploads whose row never committed (a failed upload whose cleanup also failed) left
            // their objects under the same owner-namespaced keys. A write of unknown outcome whose
            // object turns up here is recorded as applied before the object is removed, so that
            // observation is not lost.
            Map<String, ObjectWrite> unknownByKey = new HashMap<>();
            for (ObjectWrite write : jobs.unsettledWrites(job.authUserId(), WRITE_BATCH)) {
                if (!write.applied()) {
                    unknownByKey.put(write.objectKey(), write);
                }
            }
            String prefix = MediaApplicationService.objectKeyPrefix(job.authUserId());
            try {
                budgetSpent = eraseVersions(() -> storage.versionsUnder(prefix), deadline,
                        version -> recordObserved(unknownByKey.remove(version.objectKey())))
                        == Progress.BUDGET_SPENT;
            } catch (RuntimeException e) {
                failures++;
                objectFailures.increment();
                firstFailure = "key namespace: " + reasonOf(e);
            }
        }
        int outcomeUnknown = 0;
        if (failures == 0 && !budgetSpent) {
            for (ObjectWrite write : jobs.unsettledWrites(job.authUserId(), WRITE_BATCH)) {
                try {
                    WriteProgress progress = settle(write, deadline);
                    if (progress == WriteProgress.BUDGET_SPENT) {
                        budgetSpent = true;
                        break;
                    }
                    if (progress == WriteProgress.OUTCOME_UNKNOWN) {
                        outcomeUnknown++;
                    }
                } catch (RuntimeException e) {
                    failures++;
                    objectFailures.increment();
                    if (firstFailure == null) {
                        firstFailure = "object write " + write.writeId() + ": " + reasonOf(e);
                    }
                }
            }
        }
        Instant now = clock.instant();
        if (failures > 0) {
            jobs.scheduleRetry(job.ackEventId(), token, failures + " object(s) not confirmed deleted; first "
                    + firstFailure, now.plus(backoff(job.attempts() + 1)), now);
            log.warn("media erasure requestId={} objects not confirmed deleted failures={} status=RETRY_SCHEDULED",
                    job.erasureRequestId(), failures);
            return Outcome.RETRY_SCHEDULED;
        }
        if (budgetSpent) {
            budgetStops.increment();
            jobs.release(job.ackEventId(), token, now, now);
            log.info("media erasure requestId={} attempt budget spent; work continues status=RETRY_SCHEDULED",
                    job.erasureRequestId());
            return Outcome.RETRY_SCHEDULED;
        }
        if (outcomeUnknown > 0) {
            // No time bound makes such a write harmless: the store may still apply it. The job stays
            // pending and keeps looking until each write's object is observed (and then removed).
            jobs.scheduleRetry(job.ackEventId(), token, outcomeUnknown + " object write(s) of unknown outcome;"
                    + " SUCCESS waits until each is observed", now.plus(backoff(job.attempts() + 1)), now);
            log.warn("media erasure requestId={} waiting for object writes of unknown outcome count={} status=RETRY_SCHEDULED",
                    job.erasureRequestId(), outcomeUnknown);
            return Outcome.RETRY_SCHEDULED;
        }
        Boolean completed = tx.execute(status -> {
            // Fence first (the same lock order as phase 1): no media write of the owner can be
            // in flight or admitted while this decision is made and committed.
            jobs.holdOwner(job.authUserId());
            if (!jobs.lockClaim(job.ackEventId(), token, clock.instant())) {
                return Boolean.FALSE; // claim expired or taken over: only a live claim finalizes
            }
            if (jobs.countMedia(job.authUserId()) > 0 || jobs.countUnsettledWrites(job.authUserId()) > 0) {
                jobs.release(job.ackEventId(), token, clock.instant(), clock.instant());
                return Boolean.FALSE;
            }
            jobs.deleteIdempotencyRecords(job.authUserId());
            ackOutbox.append(new UserErasureAcknowledgedEvent(
                    job.ackEventId(), job.erasureRequestId(), job.authUserId(), AccountErasureHandler.SERVICE_NAME,
                    "SUCCESS", clock.instant()));
            jobs.delete(job.ackEventId());
            return Boolean.TRUE;
        });
        if (Boolean.TRUE.equals(completed)) {
            log.info("media erasure requestId={} objects confirmed status=SUCCESS_QUEUED", job.erasureRequestId());
            return Outcome.ACK_QUEUED;
        }
        return jobs.find(job.ackEventId()).isPresent() ? Outcome.RETRY_SCHEDULED : Outcome.NOT_PENDING;
    }

    /**
     * Removes listed versions and delete markers one page at a time until a fresh listing is
     * empty, checking the deadline before every storage call. A version listed again after its
     * delete returned normally means the store kept it: the attempt fails for that key.
     */
    private Progress eraseVersions(Supplier<List<StoredVersion>> listing, Instant deadline) {
        return eraseVersions(listing, deadline, version -> { });
    }

    private Progress eraseVersions(Supplier<List<StoredVersion>> listing, Instant deadline,
                                   Consumer<StoredVersion> beforeRemove) {
        Set<StoredVersion> removed = new HashSet<>();
        while (true) {
            if (expired(deadline)) {
                return Progress.BUDGET_SPENT;
            }
            List<StoredVersion> page = listing.get();
            if (page.isEmpty()) {
                return Progress.CONFIRMED_ABSENT;
            }
            for (StoredVersion version : page) {
                if (removed.contains(version)) {
                    throw new IllegalStateException("stored version still present after delete");
                }
            }
            for (StoredVersion version : page) {
                if (expired(deadline)) {
                    return Progress.BUDGET_SPENT;
                }
                beforeRemove.accept(version);
                storage.removeVersion(version);
                removed.add(version);
            }
        }
    }

    /**
     * Settles one recorded write of the user. A write of unknown outcome is settled only once its
     * object has been observed: that proves the request was applied, and a request applies at most
     * once (one PUT per upload, no client retries, a fresh key per upload). Until then it stays.
     */
    private WriteProgress settle(ObjectWrite write, Instant deadline) {
        if (!write.applied()) {
            if (expired(deadline)) {
                return WriteProgress.BUDGET_SPENT;
            }
            if (storage.versionsOf(write.bucket(), write.objectKey()).isEmpty()) {
                return WriteProgress.OUTCOME_UNKNOWN;
            }
            tx.executeWithoutResult(status -> jobs.markWriteApplied(write.writeId(), clock.instant()));
        }
        if (eraseVersions(() -> storage.versionsOf(write.bucket(), write.objectKey()), deadline)
                == Progress.BUDGET_SPENT) {
            return WriteProgress.BUDGET_SPENT;
        }
        tx.executeWithoutResult(status -> jobs.forgetWrite(write.writeId()));
        return WriteProgress.SETTLED;
    }

    private void recordObserved(ObjectWrite write) {
        if (write != null) {
            tx.executeWithoutResult(status -> jobs.markWriteApplied(write.writeId(), clock.instant()));
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be positive");
        }
    }

    private boolean expired(Instant deadline) {
        return !clock.instant().isBefore(deadline);
    }

    /**
     * Counts the failed attempt on the job and schedules the next one, if this attempt still holds
     * the claim. If even that fails, the claim's lease still expires (so the job is retried) and
     * the recording failure is attached to the original. Object keys embed the user id, so the log
     * line carries ids and exception types only; the detail is on the job row, which is deleted
     * when the erasure completes.
     */
    private void recordAttemptFailure(UUID jobId, UUID token, Integer attempts, RuntimeException failure) {
        try {
            int attempt = (attempts != null ? attempts : jobs.find(jobId).map(Job::attempts).orElse(0)) + 1;
            Instant now = clock.instant();
            jobs.scheduleRetry(jobId, token, "attempt failed: " + reasonOf(failure), now.plus(backoff(attempt)), now);
        } catch (RuntimeException recordFailure) {
            failure.addSuppressed(recordFailure);
        }
        log.warn("media erasure job {} attempt failed ({}); it stays pending with no SUCCESS",
                jobId, typesOf(failure));
        log.debug("media erasure job {} attempt failure", jobId, failure);
    }

    Duration backoff(int attempt) {
        long factor = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        Duration delay = baseBackoff.multipliedBy(factor);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    private static String reasonOf(RuntimeException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static String typesOf(Throwable failure) {
        StringBuilder types = new StringBuilder(failure.getClass().getSimpleName());
        for (Throwable cause = failure.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            types.append(" <- ").append(cause.getClass().getSimpleName());
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            types.append(" +suppressed ").append(suppressed.getClass().getSimpleName());
        }
        return types.toString();
    }
}
