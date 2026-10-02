package com.parkio.media.application;

import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.port.MediaValidationResultRepository;
import com.parkio.media.application.port.OutboxEventAppender;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.domain.MediaValidationOutcome;
import com.parkio.media.domain.MediaValidationResult;
import com.parkio.media.domain.MediaValidationType;
import com.parkio.media.domain.event.MediaUploadedEvent;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import com.parkio.media.infrastructure.persistence.mapper.MediaPersistenceMapper;
import com.parkio.media.shared.Checksums;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Synthetic uploads and storage/database inspection shared by the media erasure ITs. It talks to
 * MinIO through its own client, so a check never trusts the adapter under test.
 */
final class MediaErasureFixture {

    /** What one completed upload leaves behind. */
    record Seeded(UUID mediaId, String key, String checksum, String perceptualHash) {
    }

    private final MinioClient minio;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MediaFileJpaRepository mediaFiles;
    private final MediaValidationResultRepository validationResults;
    private final OutboxEventAppender mediaEvents;

    MediaErasureFixture(MinioClient minio, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                        MediaFileJpaRepository mediaFiles, MediaValidationResultRepository validationResults,
                        OutboxEventAppender mediaEvents) {
        this.minio = minio;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.mediaFiles = mediaFiles;
        this.validationResults = validationResults;
        this.mediaEvents = mediaEvents;
    }

    static MinioClient minioClient(String endpoint, String accessKey, String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    /**
     * A completed upload as {@code MediaApplicationService} leaves it: the object under the owner's
     * key namespace, a READY row, four PASSED validation results and a MediaUploaded outbox row.
     */
    Seeded upload(UUID owner, String bucket) throws Exception {
        byte[] content = randomContent();
        String key = "media/" + owner + "/" + UUID.randomUUID() + ".png";
        putObject(bucket, key, content);
        return register(owner, bucket, key, content);
    }

    /** The metadata of an upload whose object is already stored under {@code key}. */
    Seeded register(UUID owner, String bucket, String key, byte[] content) {
        String checksum = Checksums.sha256Hex(content);
        String perceptualHash = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Instant now = Instant.now();
        return tx.execute(status -> {
            MediaFile media = MediaFile.create(owner, bucket, key, "image/png", content.length, checksum,
                    perceptualHash, now);
            media.markReady(now);
            mediaFiles.save(MediaPersistenceMapper.toEntity(media));
            for (MediaValidationType type : List.of(MediaValidationType.FILE_SIZE, MediaValidationType.MIME_TYPE,
                    MediaValidationType.DUPLICATE, MediaValidationType.MALWARE_SCAN)) {
                validationResults.save(MediaValidationResult.of(media.id(), type, MediaValidationOutcome.PASSED,
                        null, now));
            }
            mediaEvents.append(MediaUploadedEvent.of(media, now));
            return new Seeded(media.id(), key, checksum, perceptualHash);
        });
    }

    /** An earlier owner delete: the row is soft-deleted and the object left behind. */
    void softDeleteKeepingObject(Seeded media) {
        jdbc.update("UPDATE media_files SET status = 'DELETED', deleted_at = now() WHERE id = ?", media.mediaId());
    }

    void putObject(String bucket, String key, byte[] content) throws Exception {
        minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .contentType("image/png").build());
    }

    /**
     * Every version and delete marker the store holds for exactly this key (an unversioned bucket
     * lists its one object); a HEAD backs up an empty listing.
     */
    List<String> storedVersions(String bucket, String key) throws Exception {
        List<String> versions = new ArrayList<>();
        for (Result<Item> result : minio.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(key)
                .includeVersions(true).recursive(true).build())) {
            Item item = result.get();
            if (key.equals(item.objectName())) {
                versions.add((item.isDeleteMarker() ? "delete-marker:" : "version:") + item.versionId());
            }
        }
        if (versions.isEmpty() && objectExists(bucket, key)) {
            versions.add("current");
        }
        return versions;
    }

    boolean objectExists(String bucket, String key) throws Exception {
        try {
            minio.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw e;
        }
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    long ackRows(UserErasureRequestedEvent event) {
        return count("""
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND event_type = 'UserErasureAcknowledged' AND aggregate_id = ?
                """, event.erasureRequestId());
    }

    long jobRows(UserErasureRequestedEvent event) {
        return count("SELECT COUNT(*) FROM media_erasure_jobs WHERE erasure_request_id = ?", event.erasureRequestId());
    }

    UUID jobId(UserErasureRequestedEvent event) {
        return jdbc.queryForObject("SELECT ack_event_id FROM media_erasure_jobs WHERE erasure_request_id = ?",
                UUID.class, event.erasureRequestId());
    }

    static UserErasureRequestedEvent request(UUID user) {
        return new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), user, Instant.now());
    }

    static byte[] randomContent() {
        return ("synthetic-object-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    }
}
