package com.parkio.media.application;

import com.parkio.media.application.command.UploadMediaCommand;
import com.parkio.media.application.port.MediaFileRepository;
import com.parkio.media.application.port.ImageNormalizationException;
import com.parkio.media.application.port.ImageNormalizer;
import com.parkio.media.application.port.MediaScanner;
import com.parkio.media.application.port.MediaScannerUnavailableException;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.application.port.MediaValidationResultRepository;
import com.parkio.media.application.port.OutboxEventAppender;
import com.parkio.media.application.result.MediaAccessUrl;
import com.parkio.media.application.result.MediaUploadResult;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.domain.MediaStatus;
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
 * Media use cases: validated upload (size / mime / duplicate), authorized metadata
 * and validation-result reads, short-lived access-URL generation, and
 * ownership-checked soft delete. Depends only on domain types and ports; storage,
 * JPA and Kafka concerns sit behind the ports in infrastructure (ai-context/01).
 * This service owns media metadata only — never parking or AI-validation logic
 * (ai-context/03).
 *
 * <p><b>Access model:</b> media-service has no local knowledge of parking-spot
 * visibility, so reads are restricted to the owner (or a moderator/admin). An
 * unauthorized read is answered as NOT_FOUND so media ids cannot be enumerated.
 * Public spot-photo viewing for non-owners must be mediated by parking-service
 * later (documented limitation).
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
    private final ImageNormalizer imageNormalizer;
    private final MediaScanner scanner;
    private final OutboxEventAppender outbox;
    private final MediaRejectionRecorder rejectionRecorder;
    private final MediaUploadConstraints constraints;
    private final MediaAccessUrlPolicy accessUrlPolicy;
    private final Clock clock;

    public MediaApplicationService(MediaFileRepository mediaFiles,
                                   MediaValidationResultRepository validationResults,
                                   MediaStoragePort storage,
                                   ImageNormalizer imageNormalizer,
                                   MediaScanner scanner,
                                   OutboxEventAppender outbox,
                                   MediaRejectionRecorder rejectionRecorder,
                                   MediaUploadConstraints constraints,
                                   MediaAccessUrlPolicy accessUrlPolicy,
                                   Clock clock) {
        this.mediaFiles = mediaFiles;
        this.validationResults = validationResults;
        this.storage = storage;
        this.imageNormalizer = imageNormalizer;
        this.scanner = scanner;
        this.outbox = outbox;
        this.rejectionRecorder = rejectionRecorder;
        this.constraints = constraints;
        this.accessUrlPolicy = accessUrlPolicy;
        this.clock = clock;
    }

    /**
     * Validates the upload (non-empty, allowed mime, magic-byte match, within size
     * limit, not a duplicate), <b>scans the bytes for malware</b>, then stores them
     * under a generated key and persists metadata, validation results and a
     * {@code MediaUploaded} outbox event in one transaction. The media becomes
     * {@link MediaStatus#READY} (servable) only after a clean scan.
     *
     * <p>The scan runs <em>before</em> anything is stored, so infected or unscanned
     * bytes never reach object storage. An infected scan records a {@code MediaRejected}
     * event and throws {@link MediaErrorCode#MEDIA_INFECTED} (422); a scan that cannot
     * be completed throws {@link MediaErrorCode#MEDIA_SCAN_UNAVAILABLE} (503) — the
     * upload fails closed and no media row is created. Other validation failures record
     * a {@code MediaRejected} event and throw as before.
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

        // Malware scan BEFORE storing: infected/unscanned bytes never reach storage.
        // Fail-closed — a scan that cannot run rejects the upload (no media becomes READY).
        MediaScanner.ScanReport scanReport;
        try {
            scanReport = scanner.scan(content);
        } catch (MediaScannerUnavailableException ex) {
            log.warn("Malware scan could not be completed; failing upload closed", ex);
            reject(ownerUserId, MediaValidationType.MALWARE_SCAN, "Scan could not be completed", null);
            throw new MediaException(MediaErrorCode.MEDIA_SCAN_UNAVAILABLE,
                    "The file could not be scanned. Please try again.");
        }
        if (!scanReport.clean()) {
            // The matched signature is recorded server-side for audit; never returned to the client.
            reject(ownerUserId, MediaValidationType.MALWARE_SCAN,
                    "Malware signature detected: " + scanReport.signature(), null);
            throw new MediaException(MediaErrorCode.MEDIA_INFECTED,
                    "This file failed a safety scan and was rejected.");
        }

        ImageNormalizer.NormalizedImage normalized;
        try {
            normalized = imageNormalizer.normalize(content, detectedType);
        } catch (ImageNormalizationException ex) {
            reject(ownerUserId, MediaValidationType.MIME_TYPE, "Image could not be safely normalized", null);
            throw new MediaException(MediaErrorCode.INVALID_IMAGE,
                    "Uploaded image could not be decoded or exceeded safe dimensions.");
        }

        String normalizedContentType = normalized.contentType();
        byte[] normalizedContent = normalized.content();
        String checksum = Checksums.sha256Hex(normalizedContent);
        if (mediaFiles.existsByChecksum(checksum)) {
            reject(ownerUserId, MediaValidationType.DUPLICATE, "Duplicate normalized checksum", checksum);
            throw new MediaException(MediaErrorCode.DUPLICATE_MEDIA, "This file has already been uploaded.");
        }

        Instant now = clock.instant();
        String objectKey = generateObjectKey(ownerUserId, normalizedContentType);
        MediaStoragePort.StoredObject stored = storage.store(objectKey, normalizedContent, normalizedContentType);

        MediaFile media = MediaFile.create(ownerUserId, stored.bucket(), stored.objectKey(),
                normalizedContentType, normalizedContent.length, checksum, null, now);
        media.markReady(now);
        media = mediaFiles.save(media);

        recordPassed(media.id(), MediaValidationType.FILE_SIZE, now);
        recordPassed(media.id(), MediaValidationType.MIME_TYPE, now);
        recordPassed(media.id(), MediaValidationType.DUPLICATE, now);
        recordPassed(media.id(), MediaValidationType.MALWARE_SCAN, now);

        outbox.append(MediaUploadedEvent.of(media, now));
        return MediaUploadResult.from(media);
    }

    /** Metadata for the owner (or moderator/admin); unauthorized reads see NOT_FOUND. */
    @Transactional(readOnly = true)
    public MediaFile getMetadata(UUID mediaId, UUID requesterUserId, boolean canModerate) {
        return requireReadableMedia(mediaId, requesterUserId, canModerate);
    }

    /**
     * Generates a short-lived presigned GET URL for the media object — owner or
     * moderator/admin only, and only for {@link MediaStatus#READY} media. The URL is
     * created per request and never persisted.
     */
    @Transactional(readOnly = true)
    public MediaAccessUrl createAccessUrl(UUID mediaId, UUID requesterUserId, boolean canModerate) {
        MediaFile media = requireReadableMedia(mediaId, requesterUserId, canModerate);
        return accessUrlFor(requireReady(media));
    }

    /**
     * Internal-only variant without an ownership check: the caller is a trusted
     * internal service (e.g. parking-service) that has already authorized the
     * requester against its own domain rules (spot visibility). Reached only via
     * {@code /internal/**}, which the gateway never routes and which still requires
     * the shared {@code X-Gateway-Auth} secret. Non-{@code READY} media is treated as
     * not found (no signed URL is ever issued for unscanned media).
     */
    @Transactional(readOnly = true)
    public MediaAccessUrl createAccessUrlForInternalCaller(UUID mediaId) {
        return accessUrlFor(requireReady(requireActiveMedia(mediaId)));
    }

    /**
     * Current lifecycle status of a media object, for a trusted internal caller
     * (parking-service deciding whether a spot may reference it). Deleted/unknown
     * media is {@code MEDIA_NOT_FOUND}; otherwise the live {@link MediaStatus} is
     * returned so the caller can require {@code READY}.
     */
    @Transactional(readOnly = true)
    public MediaStatus getStatusForInternalCaller(UUID mediaId) {
        return requireActiveMedia(mediaId).status();
    }

    /**
     * Current lifecycle status of a media object for a trusted internal caller that
     * needs to bind the media to a user-owned resource. Unknown, deleted, or
     * non-owned media is answered as {@code MEDIA_NOT_FOUND} so callers can fail
     * closed without turning the endpoint into an ownership oracle.
     */
    @Transactional(readOnly = true)
    public MediaStatus getStatusForInternalAttachment(UUID mediaId, UUID expectedOwnerUserId) {
        return requireReadableMedia(mediaId, expectedOwnerUserId, false).status();
    }

    private MediaAccessUrl accessUrlFor(MediaFile media) {
        Instant expiresAt = clock.instant().plus(accessUrlPolicy.ttl());
        String url = storage.generatePresignedGetUrl(media.objectKey(), accessUrlPolicy.ttl());
        return new MediaAccessUrl(media.id(), url, expiresAt);
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

    /** Validation internals for the owner (or moderator/admin); others see NOT_FOUND. */
    @Transactional(readOnly = true)
    public List<MediaValidationResult> getValidationResults(UUID mediaId, UUID requesterUserId,
                                                            boolean canModerate) {
        requireReadableMedia(mediaId, requesterUserId, canModerate);
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

    /**
     * Guards the serving paths: a signed URL is issued only for {@code READY} media.
     * Non-ready media is answered as NOT_FOUND so its existence/state cannot be probed.
     */
    private MediaFile requireReady(MediaFile media) {
        if (!media.isReady()) {
            throw new MediaException(MediaErrorCode.MEDIA_NOT_FOUND);
        }
        return media;
    }

    /**
     * Owner or moderator/admin only. Unauthorized requesters get NOT_FOUND (not
     * FORBIDDEN) so media ids cannot be probed/enumerated (IDOR prevention).
     */
    private MediaFile requireReadableMedia(UUID mediaId, UUID requesterUserId, boolean canModerate) {
        MediaFile media = requireActiveMedia(mediaId);
        if (!media.isOwnedBy(requesterUserId) && !canModerate) {
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
