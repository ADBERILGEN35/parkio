package com.parkio.media.application;

import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import com.parkio.media.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import com.parkio.media.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import com.parkio.media.infrastructure.persistence.mapper.MediaPersistenceMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Media account erasure. PRIV-001 deletes the user's media metadata and stored objects, and a
 * database commit alone does not prove the objects are gone, so SUCCESS is split in two phases
 * (docs/architecture/erasure-ack-outbox-contract.md):
 * <ol>
 *   <li>one short transaction: tombstone, soft-delete of the user's media (no longer served),
 *       idempotency records, and a durable {@code media_erasure_jobs} row — no storage I/O and no
 *       ACK;</li>
 *   <li>after commit, {@link MediaObjectErasureWorker} removes every stored version of each object
 *       outside any transaction, deletes each media row once its object is confirmed gone, and
 *       queues the SUCCESS ACK (deleting the job) only when nothing of the user is left. A failed
 *       attempt is recorded on the job and retried by the scheduled poll.</li>
 * </ol>
 */
@Service
public class AccountErasureHandler {

    public static final String SERVICE_NAME = "media";

    private static final Logger log = LoggerFactory.getLogger(AccountErasureHandler.class);

    private final ErasedUserTombstoneJpaRepository tombstones;
    private final MediaFileJpaRepository mediaFiles;
    private final MediaErasureJobStore jobs;
    private final MediaObjectErasureWorker objectEraser;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration lease;

    public AccountErasureHandler(
            ErasedUserTombstoneJpaRepository tombstones,
            MediaFileJpaRepository mediaFiles,
            MediaErasureJobStore jobs,
            MediaObjectErasureWorker objectEraser,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${parkio.media.erasure-worker.lease-ms:120000}") long leaseMs) {
        this.tombstones = tombstones;
        this.mediaFiles = mediaFiles;
        this.jobs = jobs;
        this.objectEraser = objectEraser;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.lease = Duration.ofMillis(leaseMs);
    }

    /**
     * Commits the metadata erase with its durable job (rethrowing on failure so the consumer
     * retries), then attempts the object phase once; that attempt holds the job's lease, and a
     * failed or unfinished attempt is retried by the worker's poll.
     */
    public void handle(UserErasureRequestedEvent event) {
        UUID jobId = ackEventId(event);
        tx.executeWithoutResult(status -> {
            Instant now = clock.instant();
            eraseMetadata(event.authUserId(), now);
            jobs.open(jobId, event.erasureRequestId(), event.authUserId(), now, now.plus(lease));
        });
        log.info("erasure metadata committed requestId={} service={} status=OBJECTS_PENDING",
                event.erasureRequestId(), SERVICE_NAME);
        try {
            objectEraser.process(jobId);
        } catch (RuntimeException e) {
            // The erase is committed and the job durable; never make the consumer redo it for this.
            log.warn("erasure object phase deferred requestId={} service={} ({})", event.erasureRequestId(),
                    SERVICE_NAME, e.getClass().getSimpleName());
        }
    }

    /**
     * One ACK (and job) per consumed request event: a redelivery of the same request event maps to
     * the same job, which is reopened (or opened again after it finished) so its objects are
     * re-verified, while a coordinator replay, which carries a new request eventId, opens a fresh
     * job and ACK. Auth deduplicates ACKs by eventId and keys them by (request, service).
     */
    static UUID ackEventId(UserErasureRequestedEvent event) {
        String key = event.eventId() + ":" + event.erasureRequestId() + ":" + SERVICE_NAME + ":ack";
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private void eraseMetadata(UUID authUserId, Instant now) {
        tombstones.save(new ErasedUserTombstoneEntity(authUserId, now));
        for (var entity : mediaFiles.findByOwnerUserId(authUserId)) {
            MediaFile media = MediaPersistenceMapper.toDomain(entity);
            if (!media.isDeleted()) {
                media.softDelete(now);
                mediaFiles.save(MediaPersistenceMapper.toEntity(media));
            }
        }
        jobs.deleteIdempotencyRecords(authUserId);
    }
}
