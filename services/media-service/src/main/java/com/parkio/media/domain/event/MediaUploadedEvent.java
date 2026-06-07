package com.parkio.media.domain.event;

import com.parkio.media.domain.MediaFile;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a media file is successfully stored and validated. Carries only IDs
 * and storage metadata — not another service's model (ai-context/06).
 */
public record MediaUploadedEvent(
        UUID eventId,
        UUID mediaId,
        UUID ownerUserId,
        String bucketName,
        String objectKey,
        String contentType,
        long fileSize,
        String checksum,
        Instant occurredAt) implements MediaEvent {

    public static final String TYPE = "MediaUploaded";

    public static MediaUploadedEvent of(MediaFile media, Instant occurredAt) {
        return new MediaUploadedEvent(UUID.randomUUID(), media.id(), media.ownerUserId(),
                media.bucketName(), media.objectKey(), media.contentType(), media.fileSize(),
                media.checksum(), occurredAt);
    }

    @Override
    public UUID aggregateId() {
        return mediaId;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
