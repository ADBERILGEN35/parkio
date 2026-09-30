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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Media account erasure. A database commit alone does not prove the user's media is gone, so
 * SUCCESS is split in two phases (docs/architecture/erasure-ack-outbox-contract.md):
 * <ol>
 *   <li>one short transaction: tombstone, soft-delete of the user's media metadata, idempotency
 *       records, and a durable {@code media_erasure_jobs} row — no storage I/O and no ACK;</li>
 *   <li>after commit, {@link MediaObjectErasureWorker} deletes the stored objects outside any
 *       transaction and queues the SUCCESS ACK only when every object is confirmed deleted. A
 *       failure here never propagates: the job stays pending and the scheduled poll retries it.</li>
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
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AccountErasureHandler(
            ErasedUserTombstoneJpaRepository tombstones,
            MediaFileJpaRepository mediaFiles,
            MediaErasureJobStore jobs,
            MediaObjectErasureWorker objectEraser,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.tombstones = tombstones;
        this.mediaFiles = mediaFiles;
        this.jobs = jobs;
        this.objectEraser = objectEraser;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Commits the metadata erase with its durable job (rethrowing on failure so the consumer
     * retries), then tries the object phase once; a failed object phase is retried by the worker.
     */
    public void handle(UserErasureRequestedEvent event) {
        UUID jobId = ackEventId(event);
        tx.executeWithoutResult(status -> {
            eraseMetadata(event.authUserId());
            jobs.open(jobId, event.erasureRequestId(), event.authUserId(), clock.instant());
        });
        log.info("erasure metadata committed requestId={} service={} status=OBJECTS_PENDING",
                event.erasureRequestId(), SERVICE_NAME);
        try {
            objectEraser.process(jobId);
        } catch (RuntimeException e) {
            log.warn("erasure object phase deferred requestId={} service={}", event.erasureRequestId(), SERVICE_NAME, e);
        }
    }

    /**
     * One ACK (and job) per consumed request event: a redelivery of the same request event maps to
     * the same job, which is reopened so its objects are re-verified, while a coordinator replay,
     * which carries a new request eventId, opens a fresh job and ACK. Auth deduplicates ACKs by
     * eventId and keys them by (request, service).
     */
    static UUID ackEventId(UserErasureRequestedEvent event) {
        String key = event.eventId() + ":" + event.erasureRequestId() + ":" + SERVICE_NAME + ":ack";
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private void eraseMetadata(UUID authUserId) {
        tombstones.save(new ErasedUserTombstoneEntity(authUserId, clock.instant()));
        for (var entity : mediaFiles.findByOwnerUserId(authUserId)) {
            MediaFile media = MediaPersistenceMapper.toDomain(entity);
            if (!media.isDeleted()) {
                media.softDelete(clock.instant());
                mediaFiles.save(MediaPersistenceMapper.toEntity(media));
            }
        }
        jdbc.update("DELETE FROM idempotency_records WHERE user_id = ?", authUserId);
    }
}
