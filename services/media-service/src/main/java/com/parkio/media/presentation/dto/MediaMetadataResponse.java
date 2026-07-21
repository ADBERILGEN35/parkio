package com.parkio.media.presentation.dto;

import com.parkio.media.domain.MediaFile;
import java.time.Instant;
import java.util.UUID;

/**
 * Media metadata for authorized clients. Deliberately omits all storage internals
 * (bucket name, object key, checksum): bytes are reached via the short-lived
 * access-URL endpoint, never via raw storage details.
 */
public record MediaMetadataResponse(
        UUID mediaId,
        UUID ownerUserId,
        String contentType,
        long fileSize,
        String status,
        ClaimedRegionDto claimedRegion,
        Instant createdAt,
        Instant updatedAt) {

    public static MediaMetadataResponse from(MediaFile media) {
        ClaimedRegionDto region = null;
        if (media.claimedRegion() != null) {
            var r = media.claimedRegion();
            region = new ClaimedRegionDto(r.x(), r.y(), r.width(), r.height());
        }
        return new MediaMetadataResponse(media.id(), media.ownerUserId(), media.contentType(),
                media.fileSize(), media.status().name(), region,
                media.createdAt(), media.updatedAt());
    }
}
