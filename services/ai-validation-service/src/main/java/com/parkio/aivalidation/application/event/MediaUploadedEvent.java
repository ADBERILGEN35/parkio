package com.parkio.aivalidation.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Media-service MediaUploaded payload mirror. Extra claimed-region fields are optional
 * for backward compatibility with older events.
 */
public record MediaUploadedEvent(
        UUID eventId,
        UUID mediaId,
        UUID ownerUserId,
        String contentType,
        long fileSize,
        String checksum,
        Double claimedRegionX,
        Double claimedRegionY,
        Double claimedRegionWidth,
        Double claimedRegionHeight,
        Instant occurredAt) {

    public MediaUploadedEvent(UUID eventId, UUID mediaId, UUID ownerUserId, String contentType,
                              long fileSize, String checksum, Instant occurredAt) {
        this(eventId, mediaId, ownerUserId, contentType, fileSize, checksum,
                null, null, null, null, occurredAt);
    }
}
