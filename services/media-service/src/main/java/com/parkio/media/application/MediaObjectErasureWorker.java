package com.parkio.media.application;

import com.parkio.media.application.port.ErasureAckOutbox;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.application.port.MediaStoragePort.StoredVersion;
import com.parkio.media.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.Job;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.StoredMedia;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 2 of media account erasure (docs/architecture/erasure-ack-outbox-contract.md). Outside any
 * database transaction it removes every stored version and delete marker of each of the user's
 * objects and confirms with a fresh listing that none is left; only then does a short transaction
 * delete that media row and its validation results. Once no row of the user remains and the user's
 * key namespace is confirmed empty, one transaction queues the SUCCESS ACK and deletes the job, so
 * neither media metadata nor job state outlives the erasure. A delete call that returns normally
 * is never taken as proof: an object still listed, a version the store refuses to remove (object
 * lock) or an object outside the configured bucket keeps the job pending. Every failed attempt is
 * recorded on the job (attempts, last error, backoff) and retried by the scheduled poll.
 */
@Component
public class MediaObjectErasureWorker {

    /** Result of one processing attempt. */
    public enum Outcome {
        /** Every object confirmed gone; the SUCCESS ACK is queued and the job deleted. */
        ACK_QUEUED,
        /** Something is not confirmed yet or the attempt failed; the job stays pending. */
        RETRY_SCHEDULED,
        /** The job does not exist (finished, or never opened). */
        NOT_PENDING
    }

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
            @Value("${parkio.media.erasure-worker.attempt-budget-ms:60000}") long attemptBudgetMs) {
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
        this.objectFailures = Counter.builder("parkio.media.erasure.object.delete.failed")
                .description("Stored objects not confirmed deleted during account erasure (retried)")
                .register(registry);
        this.attemptFailures = Counter.builder("parkio.media.erasure.attempt.failed")
                .description("Media erasure attempts that failed outside object deletion (retried)")
                .register(registry);
        Gauge.builder("parkio.media.erasure.jobs.pending", jobs, MediaErasureJobStore::countPendingJobs)
                .description("Media erasure jobs still waiting for confirmed object deletion (no SUCCESS yet)")
                .register(registry);
    }

    /** Retries due pending jobs (crash recovery, storage outages, backoff). */
    @Scheduled(fixedDelayString = "${parkio.media.erasure-worker.poll-interval-ms:30000}")
    public void processDue() {
        if (!schedulingEnabled) {
            return;
        }
        Instant now = clock.instant();
        for (UUID jobId : jobs.claimDue(batchSize, now, now.plus(lease))) {
            process(jobId);
        }
    }

    /**
     * Attempts the job once. A failed attempt does not throw: it is recorded on the job, which
     * stays pending with backoff, and no SUCCESS is queued.
     */
    public Outcome process(UUID jobId) {
        Integer attempts = null;
        try {
            Optional<Job> found = jobs.find(jobId);
            if (found.isEmpty()) {
                return Outcome.NOT_PENDING;
            }
            attempts = found.get().attempts();
            return attempt(found.get());
        } catch (RuntimeException failure) {
            attemptFailures.increment();
            recordAttemptFailure(jobId, attempts, failure);
            return Outcome.RETRY_SCHEDULED;
        }
    }

    private Outcome attempt(Job job) {
        Instant deadline = clock.instant().plus(attemptBudget);
        int failures = 0;
        boolean budgetSpent = false;
        String firstFailure = null;
        for (StoredMedia media : jobs.remainingMedia(job.authUserId())) {
            if (clock.instant().isAfter(deadline)) {
                // Bounds a consumer-thread attempt when the store is slow; the poll continues.
                budgetSpent = true;
                break;
            }
            try {
                eraseConfirmed(() -> storage.versionsOf(media.bucket(), media.objectKey()));
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
            // their objects under the same owner-namespaced keys.
            String prefix = MediaApplicationService.objectKeyPrefix(job.authUserId());
            try {
                eraseConfirmed(() -> storage.versionsUnder(prefix));
            } catch (RuntimeException e) {
                failures++;
                objectFailures.increment();
                firstFailure = "key namespace: " + reasonOf(e);
            }
        }
        Instant now = clock.instant();
        if (failures > 0) {
            jobs.scheduleRetry(job.ackEventId(), failures + " object(s) not confirmed deleted; first " + firstFailure,
                    now.plus(backoff(job.attempts() + 1)), now);
            log.warn("media erasure requestId={} objects not confirmed deleted failures={} status=RETRY_SCHEDULED",
                    job.erasureRequestId(), failures);
            return Outcome.RETRY_SCHEDULED;
        }
        if (budgetSpent) {
            jobs.makeDue(job.ackEventId(), now);
            return Outcome.RETRY_SCHEDULED;
        }
        Boolean completed = tx.execute(status -> {
            if (!jobs.lock(job.ackEventId())) {
                return Boolean.FALSE;
            }
            if (jobs.countMedia(job.authUserId()) > 0) {
                // Media committed after the listing above (redelivery or a late upload): go round again.
                jobs.makeDue(job.ackEventId(), clock.instant());
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
     * Removes every listed version and delete marker, then requires a fresh listing to be empty: a
     * delete call that returns normally is not proof that the bytes are gone.
     */
    private void eraseConfirmed(Supplier<List<StoredVersion>> listing) {
        for (StoredVersion version : listing.get()) {
            storage.removeVersion(version);
        }
        List<StoredVersion> left = listing.get();
        if (!left.isEmpty()) {
            throw new IllegalStateException(left.size() + " stored version(s) still present after delete");
        }
    }

    /**
     * Counts the failed attempt on the job and schedules the next one. If even that fails, the job
     * keeps its lease (so it is retried) and the recording failure is attached to the original.
     * Object keys embed the user id, so the log line carries ids and exception types only; the
     * detail is on the job row, which is deleted when the erasure completes.
     */
    private void recordAttemptFailure(UUID jobId, Integer attempts, RuntimeException failure) {
        try {
            int attempt = (attempts != null ? attempts : jobs.find(jobId).map(Job::attempts).orElse(0)) + 1;
            Instant now = clock.instant();
            jobs.scheduleRetry(jobId, "attempt failed: " + reasonOf(failure), now.plus(backoff(attempt)), now);
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
