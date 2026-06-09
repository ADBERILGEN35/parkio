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
        Instant createdAt,
        Instant updatedAt) {

    public static MediaMetadataResponse from(MediaFile media) {
        return new MediaMetadataResponse(media.id(), media.ownerUserId(), media.contentType(),
                media.fileSize(), media.status().name(), media.createdAt(), media.updatedAt());
    }
}
