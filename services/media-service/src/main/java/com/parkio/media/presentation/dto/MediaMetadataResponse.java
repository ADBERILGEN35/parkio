package com.parkio.media.presentation.dto;

import com.parkio.media.domain.MediaFile;
import java.time.Instant;
import java.util.UUID;

/**
 * Media metadata for clients. Deliberately omits raw storage internals (bucket
 * name, object key): callers get an {@code accessUrl} when one exists, not the
 * bucket layout.
 */
public record MediaMetadataResponse(
        UUID mediaId,
        UUID ownerUserId,
        String contentType,
        long fileSize,
        String checksum,
        String status,
        String accessUrl,
        Instant createdAt,
        Instant updatedAt) {

    public static MediaMetadataResponse from(MediaFile media) {
        return new MediaMetadataResponse(media.id(), media.ownerUserId(), media.contentType(),
                media.fileSize(), media.checksum(), media.status().name(), media.accessUrl(),
                media.createdAt(), media.updatedAt());
    }
}
