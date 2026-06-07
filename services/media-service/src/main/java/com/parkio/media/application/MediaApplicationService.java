package com.parkio.media.application;

import com.parkio.media.application.command.UploadMediaCommand;
import com.parkio.media.application.port.MediaFileRepository;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.application.port.MediaValidationResultRepository;
import com.parkio.media.application.port.OutboxEventAppender;
import com.parkio.media.application.result.MediaUploadResult;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.domain.MediaValidationOutcome;
import com.parkio.media.domain.MediaValidationResult;
import com.parkio.media.domain.MediaValidationType;
import com.parkio.media.domain.event.MediaRejectedEvent;
import com.parkio.media.domain.event.MediaUploadedEvent;
import com.parkio.media.domain.exception.MediaErrorCode;
import com.parkio.media.domain.exception.MediaException;
import com.parkio.media.shared.Checksums;
import com.parkio.media.shared.ImageContentTypeDetector;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Media use cases: validated upload (size / mime / duplicate), metadata retrieval,
 * ownership-checked soft delete, and validation-result reads. Depends only on
 * domain types and ports; storage, JPA and Kafka concerns sit behind the ports in
 * infrastructure (ai-context/01). This service owns media metadata only — never
 * parking or AI-validation logic (ai-context/03).
 */
@Service
@Transactional
public class MediaApplicationService {

    private static final Logger log = LoggerFactory.getLogger(MediaApplicationService.class);

    private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp");

    private final MediaFileRepository mediaFiles;
    private final MediaValidationResultRepository validationResults;
    private final MediaStoragePort storage;
    private final OutboxEventAppender outbox;
    private final MediaRejectionRecorder rejectionRecorder;
    private final MediaUploadConstraints constraints;
    private final Clock clock;

    public MediaApplicationService(MediaFileRepository mediaFiles,
                                   MediaValidationResultRepository validationResults,
                                   MediaStoragePort storage,
                                   OutboxEventAppender outbox,
                                   MediaRejectionRecorder rejectionRecorder,
                                   MediaUploadConstraints constraints,
                                   Clock clock) {
        this.mediaFiles = mediaFiles;
        this.validationResults = validationResults;
        this.storage = storage;
        this.outbox = outbox;
        this.rejectionRecorder = rejectionRecorder;
        this.constraints = constraints;
        this.clock = clock;
    }

    /**
     * Validates the upload (non-empty, allowed mime, within size limit, not a
     * duplicate), stores the bytes under a generated key, and persists metadata,
     * validation results and a {@code MediaUploaded} outbox event in one
     * transaction. Rejections record a {@code MediaRejected} event and throw.
     */
    public MediaUploadResult upload(UploadMediaCommand command) {
        UUID ownerUserId = command.ownerUserId();
        byte[] content = command.content();
        String contentType = command.contentType();

        if (content == null || content.length == 0) {
            reject(ownerUserId, MediaValidationType.FILE_SIZE, "Empty file", null);
            throw new MediaException(MediaErrorCode.EMPTY_FILE, "Uploaded file is empty.");
        }
        if (!constraints.allows(contentType)) {
            reject(ownerUserId, MediaValidationType.MIME_TYPE, "Unsupported content type: " + contentType, null);
            throw new MediaException(MediaErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported content type: " + contentType);
        }
        if (constraints.exceedsMaxSize(content.length)) {
            reject(ownerUserId, MediaValidationType.FILE_SIZE, "File exceeds maximum size", null);
            throw new MediaException(MediaErrorCode.FILE_TOO_LARGE, "Uploaded file exceeds the maximum size.");
        }

        // Don't trust the declared header: confirm the bytes really are the claimed
        // image type. A missing or conflicting detection is rejected. (Deep
        // image-safety and parking-relevance checks remain advisory, in other services.)
        String detectedType = ImageContentTypeDetector.detect(content).orElse(null);
        if (!contentType.equals(detectedType)) {
            reject(ownerUserId, MediaValidationType.MIME_TYPE,
                    "Declared content type " + contentType + " does not match detected " + detectedType, null);
            throw new MediaException(MediaErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "File content does not match the declared image type.");
        }

        String checksum = Checksums.sha256Hex(content);
        if (mediaFiles.existsByChecksum(checksum)) {
            reject(ownerUserId, MediaValidationType.DUPLICATE, "Duplicate checksum", checksum);
            throw new MediaException(MediaErrorCode.DUPLICATE_MEDIA, "This file has already been uploaded.");
        }

        Instant now = clock.instant();
        String objectKey = generateObjectKey(ownerUserId, contentType);
        MediaStoragePort.StoredObject stored = storage.store(objectKey, content, contentType);

        MediaFile media = MediaFile.create(ownerUserId, stored.bucket(), stored.objectKey(),
                stored.accessUrl(), contentType, content.length, checksum, null, now);
        media.markValidated(now);
        media = mediaFiles.save(media);

        recordPassed(media.id(), MediaValidationType.FILE_SIZE, now);
        recordPassed(media.id(), MediaValidationType.MIME_TYPE, now);
        recordPassed(media.id(), MediaValidationType.DUPLICATE, now);

        outbox.append(MediaUploadedEvent.of(media, now));
        return MediaUploadResult.from(media);
    }

    @Transactional(readOnly = true)
    public MediaFile getMetadata(UUID mediaId) {
        return requireActiveMedia(mediaId);
    }

    /** Soft-deletes the media (owner only) and best-effort removes the stored object. */
    public void delete(UUID mediaId, UUID requesterUserId) {
        MediaFile media = requireActiveMedia(mediaId);
        if (!media.isOwnedBy(requesterUserId)) {
            throw new MediaException(MediaErrorCode.NOT_MEDIA_OWNER, "You do not own this media.");
        }
        media.softDelete(clock.instant());
        mediaFiles.save(media);

        // The bytes are owned solely by this service and a soft-deleted record is
        // never served again, so removing the object is safe. Best-effort: a
        // storage failure must not fail the (already-committed-intent) delete.
        try {
            storage.delete(media.objectKey());
        } catch (RuntimeException ex) {
            log.warn("Soft-deleted media {} but failed to remove its storage object", mediaId, ex);
        }
    }

    @Transactional(readOnly = true)
    public List<MediaValidationResult> getValidationResults(UUID mediaId) {
        requireActiveMedia(mediaId);
        return validationResults.findByMediaId(mediaId);
    }

    private MediaFile requireActiveMedia(UUID mediaId) {
        MediaFile media = mediaFiles.findById(mediaId)
                .orElseThrow(() -> new MediaException(MediaErrorCode.MEDIA_NOT_FOUND));
        if (media.isDeleted()) {
            throw new MediaException(MediaErrorCode.MEDIA_NOT_FOUND);
        }
        return media;
    }

    private void recordPassed(UUID mediaId, MediaValidationType type, Instant now) {
        validationResults.save(MediaValidationResult.of(mediaId, type, MediaValidationOutcome.PASSED, null, now));
    }

    private void reject(UUID ownerUserId, MediaValidationType type, String reason, String checksum) {
        // Recorded in its own transaction so it survives the rollback caused by the
        // exception the caller throws immediately after.
        rejectionRecorder.record(MediaRejectedEvent.of(ownerUserId, type, reason, checksum, clock.instant()));
    }

    private static String generateObjectKey(UUID ownerUserId, String contentType) {
        String extension = EXTENSION_BY_CONTENT_TYPE.getOrDefault(contentType, "");
        // Owner id (a UUID) namespaces the key; the filename is never user-derived.
        return "media/" + ownerUserId + "/" + UUID.randomUUID() + extension;
    }
}
