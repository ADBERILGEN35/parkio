package com.parkio.media.application;

import com.parkio.media.application.port.ErasureAckOutbox;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.Job;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.PendingObject;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 2 of media account erasure: deletes the erased user's stored objects outside any database
 * transaction and queues the media SUCCESS ACK only once every soft-deleted object of the user is
 * confirmed deleted (an already-absent object counts as deleted). Progress is durable
 * ({@code media_files.object_deleted_at}, {@code media_erasure_jobs}), so a crash or a storage
 * outage never loses work and never reports SUCCESS early: a failed attempt leaves the job pending
 * with backoff, and the scheduled poll retries due jobs (docs/architecture/erasure-ack-outbox-contract.md).
 */
@Component
public class MediaObjectErasureWorker {

    /** Result of one processing attempt. */
    public enum Outcome {
        /** Every object confirmed; the SUCCESS ACK is queued in the outbox. */
        ACK_QUEUED,
        /** Some object is not confirmed yet; the job stays pending and is retried later. */
        RETRY_SCHEDULED,
        /** The job does not exist or is no longer pending. */
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
    private final Counter objectDeleteFailures;

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
            @Value("${parkio.media.erasure-worker.max-backoff-ms:900000}") long maxBackoffMs) {
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
        this.objectDeleteFailures = Counter.builder("parkio.media.erasure.object.delete.failed")
                .description("Stored-object deletions that failed during account erasure (retried)")
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
            try {
                process(jobId);
            } catch (RuntimeException e) {
                log.warn("media erasure job {} attempt failed; it stays pending", jobId, e);
            }
        }
    }

    /**
     * Deletes the job's unconfirmed objects, then queues the SUCCESS ACK if none remain. Storage
     * failures never propagate: they leave the job pending and schedule a retry.
     */
    public Outcome process(UUID jobId) {
        Optional<Job> found = jobs.find(jobId);
        if (found.isEmpty() || !found.get().pending()) {
            return Outcome.NOT_PENDING;
        }
        Job job = found.get();
        int failures = 0;
        String firstFailure = null;
        for (PendingObject object : jobs.pendingObjects(job.authUserId())) {
            try {
                storage.delete(object.objectKey());
                jobs.markObjectDeleted(object.mediaId(), clock.instant());
            } catch (RuntimeException e) {
                failures++;
                objectDeleteFailures.increment();
                if (firstFailure == null) {
                    firstFailure = "media " + object.mediaId() + ": " + reasonOf(e);
                }
            }
        }
        if (failures > 0) {
            Instant now = clock.instant();
            jobs.scheduleRetry(jobId, failures + " object(s) not deleted; first " + firstFailure,
                    now.plus(backoff(job.attempts() + 1)), now);
            log.warn("media erasure requestId={} objects pending failures={} status=RETRY_SCHEDULED",
                    job.erasureRequestId(), failures);
            return Outcome.RETRY_SCHEDULED;
        }
        Boolean completed = tx.execute(status -> {
            if (!jobs.lockPending(jobId)) {
                return Boolean.FALSE;
            }
            if (jobs.countPendingObjects(job.authUserId()) > 0) {
                // A redelivery soft-deleted more rows after the listing above: go round again.
                jobs.makeDue(jobId, clock.instant());
                return Boolean.FALSE;
            }
            ackOutbox.append(new UserErasureAcknowledgedEvent(
                    jobId, job.erasureRequestId(), job.authUserId(), AccountErasureHandler.SERVICE_NAME,
                    "SUCCESS", clock.instant()));
            jobs.markAckQueued(jobId, clock.instant());
            return Boolean.TRUE;
        });
        if (Boolean.TRUE.equals(completed)) {
            log.info("media erasure requestId={} objects confirmed status=SUCCESS_QUEUED", job.erasureRequestId());
            return Outcome.ACK_QUEUED;
        }
        return jobs.find(jobId).map(Job::pending).orElse(false) ? Outcome.RETRY_SCHEDULED : Outcome.NOT_PENDING;
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
}
