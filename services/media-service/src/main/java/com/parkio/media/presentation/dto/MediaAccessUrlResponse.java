package com.parkio.media.presentation.dto;

import com.parkio.media.application.result.MediaAccessUrl;
import java.time.Instant;
import java.util.UUID;

/**
 * A short-lived presigned GET URL for viewing a media object. Generated per
 * authorized request and never persisted; carries no bucket/object-key fields.
 */
public record MediaAccessUrlResponse(UUID mediaId, String accessUrl, Instant expiresAt) {

    public static MediaAccessUrlResponse from(MediaAccessUrl accessUrl) {
        return new MediaAccessUrlResponse(accessUrl.mediaId(), accessUrl.url(), accessUrl.expiresAt());
    }
}
