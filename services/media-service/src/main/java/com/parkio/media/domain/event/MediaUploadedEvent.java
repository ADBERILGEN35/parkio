package com.parkio.media.domain.event;

import com.parkio.media.domain.ClaimedRegion;
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
        Double claimedRegionX,
        Double claimedRegionY,
        Double claimedRegionWidth,
        Double claimedRegionHeight,
        Instant occurredAt) implements MediaEvent {

    public static final String TYPE = "MediaUploaded";

    public static MediaUploadedEvent of(MediaFile media, Instant occurredAt) {
        ClaimedRegion region = media.claimedRegion();
        return new MediaUploadedEvent(UUID.randomUUID(), media.id(), media.ownerUserId(),
                media.bucketName(), media.objectKey(), media.contentType(), media.fileSize(),
                media.checksum(),
                region == null ? null : region.x(),
                region == null ? null : region.y(),
                region == null ? null : region.width(),
                region == null ? null : region.height(),
                occurredAt);
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
